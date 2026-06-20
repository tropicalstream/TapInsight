package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.TapLink.app.media.MediaLibraryService
import com.rayneo.visionclaw.core.network.ResearchRouter
import com.rayneo.visionclaw.core.storage.ReadableArtifactStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ResearchTool(
    context: Context,
    private val researchRouter: ResearchRouter
) : AiTapTool {

    override val name: String = "research_topic"
    private val artifactStore = ReadableArtifactStore(context)
    private val mediaLibrary = MediaLibraryService(context)

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val topic = args["topic"]?.trim().orEmpty()
        if (topic.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing topic for research request."))
        }

        // mode='links' switches to URL-list output (used when the user wants
        // a researched list of sources, not a prose report). Default 'report'
        // preserves the existing 2–3 paragraph analytical brief.
        val mode = args["mode"]?.trim()?.lowercase().orEmpty()
        val isLinksMode = mode == "links" || mode == "url" || mode == "urls" || mode == "sources"

        // Optional media-type filter: when the user explicitly named a
        // subset (video / podcast / audio / article / pdf), Gemini passes
        // it through here so the research prompt can bias its search
        // toward sites that match those types. Empty/unset means "no
        // restriction"; codex/web_search will default to encyclopedic
        // sources, which is fine for unconstrained research queries.
        val rawMediaTypes = args["mediaTypes"]?.trim().orEmpty().ifBlank {
            args["media_types"]?.trim().orEmpty()
        }
        val mediaTypes = rawMediaTypes
            .split(',', ' ', '|', '/')
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .distinct()

        val result = if (isLinksMode) {
            researchRouter.researchLinks(topic, mediaTypes)
        } else {
            researchRouter.research(topic)
        }

        return when (result) {
            is ResearchRouter.ResearchResult.Success -> {
                val text = result.text.trim()
                // Save the artifact for the report-mode path; links-mode
                // output is meant to be ephemeral chat-card content, not a
                // saved research report.
                if (!isLinksMode) {
                    artifactStore.saveResearchReport(
                        topic = topic,
                        title = "Research report: $topic",
                        text = text,
                        sourceLabel = result.model
                    )
                    saveResearchReportToMediaLibrary(
                        topic = topic,
                        text = text,
                        sourceLabel = result.model
                    )
                }
                Result.success(text)
            }
            is ResearchRouter.ResearchResult.ApiKeyMissing ->
                Result.failure(IllegalStateException("Research provider API key missing."))
            is ResearchRouter.ResearchResult.Error ->
                Result.failure(IllegalStateException(result.message))
        }
    }

    private fun saveResearchReportToMediaLibrary(
        topic: String,
        text: String,
        sourceLabel: String?
    ): String? {
        return runCatching {
            mediaLibrary.ensureBootstrap()
            val textDir = mediaLibrary.resolveSafe(MediaLibraryService.DEFAULT_TEXT_DIR)
                ?: File(mediaLibrary.mediaRoot, MediaLibraryService.DEFAULT_TEXT_DIR)
            textDir.mkdirs()

            val createdAt = Date()
            val filenameStamp = FILENAME_DATE_FORMAT.format(createdAt)
            val bodyStamp = BODY_DATE_FORMAT.format(createdAt)
            val topicPart = slugForFilename(topic).ifBlank { "Research" }
            val filename = uniqueTextFile(textDir, "Research - $topicPart - $filenameStamp.txt")
            val target = File(textDir, filename)
            val header = buildString {
                appendLine("Research report: ${topic.trim()}")
                appendLine("Generated: $bodyStamp")
                sourceLabel?.trim()?.takeIf { it.isNotBlank() }?.let { appendLine("Source: $it") }
                appendLine()
            }
            target.writeText(header + text.trim() + "\n", Charsets.UTF_8)
            "${MediaLibraryService.DEFAULT_TEXT_DIR}/$filename"
        }.onFailure {
            Log.w(TAG, "Failed to save research report to media library", it)
        }.getOrNull()
    }

    private fun uniqueTextFile(dir: File, preferredName: String): String {
        val base = preferredName.removeSuffix(".txt")
        var candidate = preferredName
        var i = 2
        while (File(dir, candidate).exists()) {
            candidate = "$base-$i.txt"
            i++
        }
        return candidate
    }

    private fun slugForFilename(raw: String): String {
        return raw
            .trim()
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("[^A-Za-z0-9 ._'-]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '.', '_', '-')
            .take(72)
            .ifBlank { "Research" }
    }

    private companion object {
        private const val TAG = "ResearchTool"
        private val FILENAME_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US)
        private val BODY_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}
