package com.newoether.agora.phone

import android.graphics.Bitmap
import com.newoether.agora.model.RunEffectIdentity
import com.newoether.agora.model.ToolImageAttachment
import com.newoether.agora.tool.ToolExecutionResult
import com.newoether.agora.tool.ToolProvider
import com.newoether.agora.viewmodel.GenerationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*

/** Normal tool provider. The existing executor supplies the exact authorized batch identity. */
internal class PhoneToolProvider(
    private val lease: PhoneControlLease,
    private val settings: () -> PhoneUseSettings,
    private val accepts: (RunEffectIdentity) -> Boolean,
    private val title: suspend (String) -> String,
    private val ready: suspend (String) -> Boolean,
    private val locked: () -> Boolean,
    private val currentReady: (String) -> Boolean = { true },
    private val device: (String) -> PhoneDevice,
    private val ownPackage: String,
    private val launcher: () -> String?,
    private val persist: suspend (Bitmap) -> ToolImageAttachment,
    private val captured: suspend (suspend () -> Bitmap) -> Bitmap,
    private val showControl: suspend (String) -> Unit,
    private val action: (String, Int, Int, Int, Int, Long) -> Unit,
    private val status: (String?) -> Unit,
) : ToolProvider {
    private var executionMode = "accessibility"
    private val executing = AtomicBoolean(false)
    private var screen: Pair<Long, PhoneScreenSpace>? = null
    private var treePackage: String? = null
    private var tree: Triple<Long, Triple<Int, Int, Int>, List<PhoneNode>>? = null
    override fun definitions(ctx: GenerationContext) = phoneDefinitions(ctx)
    override fun handles(name: String) = name in PHONE_TOOL_NAMES
    override suspend fun execute(name: String, arguments: String, ctx: GenerationContext): String =
        executeOneShotResult(name, arguments, ctx).text
    override suspend fun executeOneShotResult(name: String, arguments: String, ctx: GenerationContext): ToolExecutionResult {
        val identity = ctx.toolEffectIdentity ?: return failure("stale_identity")
        // Refusals precede parsing, readiness probes, app resolution and every other device effect.
        val decision = if (name == "phone_claim") lease.claimRefusal(identity) else lease.authorize(identity)
        if (decision is PhoneControlLease.Decision.Refused) return refused(decision)
        if (!accepts(identity)) return failure("stale_identity")
        if (definitions(ctx).none { it.function.name == name }) return failure("not_admitted")
        // Concurrent device operations fail at a bounded decision; no lock spans platform work.
        if (!executing.compareAndSet(false, true)) return failure("phone_busy")
        return run {
            executionMode = ctx.phoneMode
            var permit = (decision as? PhoneControlLease.Decision.Granted)?.permit
            try {
                if (!accepts(identity)) throw PhoneFailure("stale_identity")
                permit?.let { fence(it) }
                if (name == "phone_claim") lease.claimRefusal(identity)?.let { return@run refused(it) }
                val config = settings()
                if (!config.enabled || config.mode != ctx.phoneMode || !ready(ctx.phoneMode)) {
                    lease.releaseOwner(identity.owner(), "permission_lost")
                    throw PhoneFailure("permission_lost")
                }
                permit?.let { fence(it) }
                if (!accepts(identity)) throw PhoneFailure("stale_identity")
                if (name == "phone_claim") lease.claimRefusal(identity)?.let { return@run refused(it) }
                if (locked()) {
                    lease.releaseOwner(identity.owner(), "screen_locked")
                    throw PhoneFailure("screen_locked")
                }
                val args = Json.parseToJsonElement(arguments.ifBlank { "{}" }) as? JsonObject
                    ?: throw PhoneFailure("bad_arguments")
                if (name == "phone_claim") {
                    // Occupied is a pure lease decision. Do not start a service or alter windows.
                    val conversationTitle = title(identity.conversationId)
                    if (!accepts(identity)) throw PhoneFailure("stale_identity")
                    when (val claim = lease.claim(identity, conversationTitle)) {
                        is PhoneControlLease.Decision.Refused -> return@run refused(claim)
                        is PhoneControlLease.Decision.Granted -> permit = claim.permit
                    }
                    fence(checkNotNull(permit))
                    try { showControl(ctx.phoneMode) } catch (error: Exception) {
                        lease.releaseOwner(identity.owner(), "overlay_failed"); throw error
                    }
                    fence(checkNotNull(permit))
                    return@run success(buildJsonObject { put("status", "claimed"); put("idle_seconds", 300) })
                }
                val authorized = checkNotNull(permit)
                fence(authorized)
                if (name == "phone_release") {
                    lease.release(authorized)?.let { throw PhoneFailure(it) }
                    return@run success(buildJsonObject { put("status", "released") })
                }
                val backend = device(ctx.phoneMode)
                status(name)
                checkApp(backend, authorized)
                val body = buildJsonObject {
                    put("status", "ok")
                    when (name) {
                        "phone_screenshot" -> Unit
                        "phone_ui_tree" -> {
                            val dimensions = backend.dimensions()
                            val nodes = backend.nodes()
                            fence(authorized)
                            checkApp(backend, authorized)
                            treePackage = backend.foreground()
                            tree = Triple(authorized.epoch, dimensions, nodes)
                            put("nodes", JsonArray(nodes.map { node -> buildJsonObject {
                                put("index", node.index); put("text", node.text); put("role", node.role)
                                put("bounds", JsonArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom).map(::JsonPrimitive)))
                                put("enabled", node.enabled); put("selected", node.selected)
                                put("editable", node.editable); put("password", node.password)
                            } }))
                        }
                        "phone_tap", "phone_long_press" -> {
                            val (x, y) = point(args, authorized, backend)
                            val duration = if (name == "phone_tap") 60L else args.long("duration_ms", 800).coerceIn(500, 10_000)
                            fence(authorized); action(name, x, y, x, y, duration)
                            backend.tap(x, y, duration)
                        }
                        "phone_swipe" -> {
                            val (x, y) = point(args, authorized, backend)
                            val (endX, endY) = coordinates(args, "end_x", "end_y", authorized, backend)
                            val duration = args.long("duration_ms", 400).coerceIn(100, 10_000)
                            fence(authorized); action(name, x, y, endX, endY, duration)
                            backend.swipe(x, y, endX, endY, duration)
                        }
                        "phone_type" -> { fence(authorized); backend.type(args.string("text")) }
                        "phone_key" -> { fence(authorized); backend.key(args.string("key")) }
                        "phone_app_launch" -> {
                            val pkg = args.string("package")
                            if (!allowed(pkg)) throw PhoneFailure("app_blocked", pkg)
                            fence(authorized); backend.launch(pkg)
                        }
                        "phone_app_list" -> put("apps", JsonArray(backend.apps().filter { allowed(it.packageName) }.map {
                            buildJsonObject { put("package", it.packageName); put("label", it.label) }
                        }))
                        else -> throw PhoneFailure("unknown_tool")
                    }
                }
                if (name in ACTIONS) { tree = null; treePackage = null }
                fence(authorized)
                val wantsScreenshot = name == "phone_screenshot" || (name in ACTIONS && args["screenshot"]?.jsonPrimitive?.booleanOrNull != false)
                val result = if (wantsScreenshot) {
                    if (name in ACTIONS) delay(180)
                    checkApp(backend, authorized)
                    val bitmap = captured { fence(authorized); checkApp(backend, authorized); backend.screenshot() }
                    try {
                        fence(authorized)
                        checkApp(backend, authorized)
                        val dimensions = backend.dimensions()
                        val image = persist(bitmap)
                        fence(authorized)
                        screen = authorized.epoch to PhoneScreenSpace(checkNotNull(image.width), checkNotNull(image.height), dimensions.first, dimensions.second, dimensions.third)
                        ToolExecutionResult(JsonObject(body + mapOf("width" to JsonPrimitive(image.width), "height" to JsonPrimitive(image.height))).toString(),
                            images = listOf(image), transcribeImages = true)
                    } finally { bitmap.recycle() }
                } else success(body)
                fence(authorized)
                lease.complete(authorized)?.let { throw PhoneFailure(it) }
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val fenced = permit?.let { lease.validate(it) }
                failure(fenced ?: (error as? PhoneFailure)?.code ?: "phone_failed", error.message)
            } finally {
                try { status(null) } finally { executing.set(false) }
            }
        }
    }
    private fun fence(permit: PhoneControlLease.Permit) {
        lease.validate(permit)?.let { throw PhoneFailure(it) }
        if (!accepts(permit.identity)) throw PhoneFailure("stale_identity")
        if (!settings().let { it.enabled && it.mode == executionMode } || !currentReady(executionMode)) {
            lease.releaseOwner(permit.identity.owner(), "permission_lost")
            throw PhoneFailure("permission_lost")
        }
        if (locked()) { lease.releaseOwner(permit.identity.owner(), "screen_locked"); throw PhoneFailure("screen_locked") }
    }
    private fun allowed(pkg: String): Boolean = settings().let {
        phoneAppAllowed(pkg, ownPackage, it.accessMode, it.packages, launcher())
    }
    private suspend fun checkApp(backend: PhoneDevice, permit: PhoneControlLease.Permit) {
        fence(permit)
        val pkg = backend.foreground()
        fence(permit)
        if (allowed(pkg)) return
        backend.key("back")
        delay(150); fence(permit)
        if (!allowed(backend.foreground())) { fence(permit); backend.key("home") }
        throw PhoneFailure("app_blocked", pkg)
    }
    private suspend fun point(args: JsonObject, permit: PhoneControlLease.Permit, backend: PhoneDevice): Pair<Int, Int> {
        val node = args["node"]?.jsonPrimitive?.intOrNull
        if (node == null) return coordinates(args, "x", "y", permit, backend)
        val snapshot = tree?.takeIf { it.first == permit.epoch && it.second == backend.dimensions() }
            ?: throw PhoneFailure("ui_tree_required")
        if (treePackage != backend.foreground()) throw PhoneFailure("ui_tree_required")
        val bounds = snapshot.third.getOrNull(node)?.bounds ?: throw PhoneFailure("invalid_node")
        return bounds.centerX() to bounds.centerY()
    }
    private fun coordinates(args: JsonObject, x: String, y: String, permit: PhoneControlLease.Permit, backend: PhoneDevice): Pair<Int, Int> {
        val space = screen?.takeIf { it.first == permit.epoch }?.second ?: throw PhoneFailure("screenshot_required")
        if (Triple(space.physicalWidth, space.physicalHeight, space.rotation) != backend.dimensions()) throw PhoneFailure("screen_changed")
        return space.physical(args[x]?.jsonPrimitive?.floatOrNull ?: throw PhoneFailure("bad_coordinates"),
            args[y]?.jsonPrimitive?.floatOrNull ?: throw PhoneFailure("bad_coordinates"))
    }
    private fun refused(value: PhoneControlLease.Decision.Refused) = failure(value.code,
        value.title?.let { "$it (${value.remainingMs} ms remaining)" })
    private fun failure(code: String, detail: String? = null) = ToolExecutionResult(buildJsonObject {
        put("error", code)
        put("message", code.replace('_', ' ') + detail?.takeIf { it != code && it.isNotBlank() }
            ?.let { ": ${it.take(1500)}" }.orEmpty())
        detail?.let { put("detail", it.take(1500)) }
    }.toString(), isError = true)
    private fun success(value: JsonObject) = ToolExecutionResult(value.toString())
    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content ?: throw PhoneFailure("bad_arguments", "$key is required")
    private fun JsonObject.long(key: String, default: Long) = this[key]?.jsonPrimitive?.longOrNull ?: default
    companion object { private val ACTIONS = setOf("phone_tap", "phone_long_press", "phone_swipe", "phone_type", "phone_key", "phone_app_launch") }
}
