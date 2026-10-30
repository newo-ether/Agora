package com.newoether.agora.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newoether.agora.AgoraApplication
import com.newoether.agora.data.local.MaintenanceDebtDao
import com.newoether.agora.data.local.MaintenanceDebtEntity
import com.newoether.agora.util.DebugLog
import com.newoether.agora.viewmodel.AttachmentOrphanSweeper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class MaintenanceDebtWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val container = (applicationContext as AgoraApplication).awaitContainer()
            ?: return@withContext Result.retry()
        val debtDao = container.database.maintenanceDebtDao()
        val attachmentSweeper = AttachmentOrphanSweeper(
            database = container.database,
            filesDirectory = applicationContext.filesDir,
        )
        val claimId = id.toString()
        var retryNeeded = false

        repeat(MAX_CLAIM_BATCHES_PER_RUN) {
            val claimedAt = System.currentTimeMillis()
            val claims = debtDao.claim(
                claimId = claimId,
                claimedAt = claimedAt,
                staleBefore = claimedAt - CLAIM_STALE_AFTER_MS,
                limit = CLAIM_BATCH_SIZE,
            )
            if (claims.isEmpty()) {
                return@withContext if (debtDao.hasDebt()) Result.retry() else Result.success()
            }
            for (claim in claims) {
                try {
                    if (processClaim(claim, debtDao, attachmentSweeper)) {
                        debtDao.complete(claim)
                    } else {
                        debtDao.release(claim, System.currentTimeMillis())
                        retryNeeded = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    DebugLog.e(TAG, "Maintenance debt failed: ${claim.kind}/${claim.identity}", error)
                    try {
                        debtDao.release(claim, System.currentTimeMillis())
                    } catch (releaseError: Exception) {
                        DebugLog.e(TAG, "Maintenance debt release failed", releaseError)
                        return@withContext Result.retry()
                    }
                    retryNeeded = true
                }
            }
            if (retryNeeded) return@withContext Result.retry()
            yield()
        }

        if (retryNeeded || debtDao.hasDebt()) Result.retry() else Result.success()
    }

    private suspend fun processClaim(
        claim: MaintenanceDebtEntity,
        debtDao: MaintenanceDebtDao,
        attachmentSweeper: AttachmentOrphanSweeper,
    ): Boolean {
        return when (claim.kind) {
            MaintenanceDebtEntity.KIND_ATTACHMENT_ORPHANS -> {
                if (claim.identity == MaintenanceDebtEntity.RECONCILE_IDENTITY) {
                    attachmentSweeper.sweep()
                } else {
                    attachmentSweeper.deleteExact(claim.identity)
                }
            }

            MaintenanceDebtEntity.KIND_EMBEDDING_ORPHANS -> {
                if (claim.identity == MaintenanceDebtEntity.RECONCILE_IDENTITY) {
                    reconcileEmbeddings(debtDao)
                } else {
                    debtDao.deleteOrphanEmbeddingsForMessage(claim.identity)
                }
                true
            }

            MaintenanceDebtEntity.KIND_STALE_EMBEDDINGS -> {
                var afterId = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val ids = debtDao.getModelEmbeddingIdsPage(claim.identity, afterId, RECONCILE_PAGE_SIZE)
                    if (ids.isEmpty()) break
                    debtDao.deleteStaleEmbeddingIds(ids)
                    afterId = ids.last()
                    yield()
                }
                true
            }

            MaintenanceDebtEntity.KIND_RUN_BRANCHES -> {
                if (claim.identity == MaintenanceDebtEntity.RECONCILE_IDENTITY) {
                    reconcileRunBranches(debtDao)
                } else {
                    debtDao.repairRunBranches(claim.identity)
                }
                true
            }

            else -> error("Unknown maintenance debt kind ${claim.kind}")
        }
    }

    private suspend fun reconcileEmbeddings(debtDao: MaintenanceDebtDao) {
        var afterId = 0L
        while (true) {
            val ids = debtDao.getEmbeddingMaintenanceIdsPage(afterId, RECONCILE_PAGE_SIZE)
            if (ids.isEmpty()) return
            debtDao.deleteOrphanEmbeddingsByIds(ids)
            afterId = ids.last()
            if (ids.size < RECONCILE_PAGE_SIZE) return
            yield()
        }
    }

    private suspend fun reconcileRunBranches(debtDao: MaintenanceDebtDao) {
        var afterId: String? = null
        while (true) {
            val ids = debtDao.getRunBranchConversationIdsPage(afterId, RECONCILE_PAGE_SIZE)
            if (ids.isEmpty()) return
            ids.forEach { conversationId -> debtDao.repairRunBranches(conversationId) }
            afterId = ids.last()
            if (ids.size < RECONCILE_PAGE_SIZE) return
            yield()
        }
    }

    companion object {
        private const val TAG = "MaintenanceDebt"
        private const val UNIQUE_WORK_NAME = "maintenance_debt"
        private const val CLAIM_BATCH_SIZE = 16
        private const val MAX_CLAIM_BATCHES_PER_RUN = 4
        private const val RECONCILE_PAGE_SIZE = 64
        private const val CLAIM_STALE_AFTER_MS = 15 * 60 * 1000L

        fun schedule(workManager: WorkManager = WorkManager.getInstance()) {
            val request = OneTimeWorkRequestBuilder<MaintenanceDebtWorker>()
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
