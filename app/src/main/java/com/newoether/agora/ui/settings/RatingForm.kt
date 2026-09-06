package com.newoether.agora.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Composable
fun RatingForm(
    onSubmitted: () -> Unit = {}
) {
    val context = LocalContext.current
    var rating by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun jsonEscape(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    Column(
        Modifier.clearFocusOnTap()
    ) {
        Text(
            text = stringResource(R.string.rating_title),
            style = ChatType.ratingTitle,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.about_rating_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        // Stars
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)
        ) {
            for (i in 1..5) {
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val isSelected = i <= rating
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.85f else if (isSelected) 1.15f else 1f,
                    animationSpec = tween(150), label = "starScale"
                )
                IconButton(
                    onClick = { rating = if (rating == i) 0 else i },
                    interactionSource = interactionSource,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = stringResource(R.string.rating_star, i),
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier
                            .size(36.dp)
                            .scale(scale)
                    )
                }
                if (i < 5) Spacer(modifier = Modifier.width(2.dp))
            }
        }

        // Name
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.rating_your_name)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        )

        // Email
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.rating_your_email)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
        )

        // Comment
        val commentMaxLen = 10000
        val commentLen = comment.length
        OutlinedTextField(
            value = comment,
            onValueChange = { if (it.length <= commentMaxLen) comment = it },
            label = { Text(stringResource(R.string.rating_comment)) },
            minLines = 3,
            maxLines = 5,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)
        )
        Text(
            text = "$commentLen/$commentMaxLen",
            style = MaterialTheme.typography.labelSmall,
            color = if (commentLen >= commentMaxLen) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
        )

        // Privacy notice
        Text(
            text = stringResource(R.string.rating_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        )

        // Error message
        if (submitError) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.rating_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Submit button — never actually disabled (the if in onClick guards submission), but it
        // shows the standard M3 disabled colors when not ready, animated smoothly between states.
        val isReady = rating > 0 && !submitting && !submitted
        val btnContainerColor by animateColorAsState(
            if (isReady) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            tween(300), label = "ratingBtnContainer"
        )
        val btnContentColor by animateColorAsState(
            if (isReady) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            tween(300), label = "ratingBtnContent"
        )
        Button(
            onClick = {
                if (isReady) {
                    scope.launch {
                        submitting = true
                        submitError = false
                        try {
                            val submittedRating = rating
                            val submittedName = name
                            val submittedEmail = email
                            val submittedComment = comment
                            val accepted = withContext(Dispatchers.IO) {
                                val json = buildString {
                                    append(
                                        "{\"rating\":$submittedRating,\"app\":" +
                                            jsonEscape(context.packageName)
                                    )
                                    if (submittedName.isNotBlank()) {
                                        append(",\"name\":${jsonEscape(submittedName)}")
                                    }
                                    if (submittedEmail.isNotBlank()) {
                                        append(",\"email\":${jsonEscape(submittedEmail)}")
                                    }
                                    if (submittedComment.isNotBlank()) {
                                        append(",\"comment\":${jsonEscape(submittedComment)}")
                                    }
                                    append("}")
                                }
                                val body = json.toRequestBody("application/json".toMediaType())
                                val request = Request.Builder()
                                    .url("https://newoether.com/api/rating")
                                    .post(body)
                                    .build()
                                HttpClient.client.newCall(request).execute().use {
                                    it.isSuccessful
                                }
                            }
                            if (!accepted) error("Rating endpoint rejected the request")
                            submitted = true
                            onSubmitted()
                        } catch (_: Exception) {
                            submitError = true
                        } finally {
                            submitting = false
                        }
                    }
                }
            },
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = btnContainerColor,
                contentColor = btnContentColor
            ),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Crossfade(
                targetState = submitting to submitted,
                modifier = Modifier.fillMaxSize(),
                animationSpec = tween(250),
                label = "ratingBtn",
            ) { (loading, done) ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = btnContentColor
                        )
                        done -> Text(
                            text = stringResource(R.string.rating_success),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                        else -> Text(
                            text = stringResource(R.string.rating_submit),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
