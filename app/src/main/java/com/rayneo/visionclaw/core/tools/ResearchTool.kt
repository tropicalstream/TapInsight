package com.rayneo.visionclaw.core.tools

import android.content.Context
import com.rayneo.visionclaw.core.network.ResearchRouter
import com.rayneo.visionclaw.core.storage.ReadableArtifactStore

class ResearchTool(
    context: Context,
    private val researchRouter: ResearchRouter
) : AiTapTool {

    override val name: String = "research_topic"
    private val artifactStore = ReadableArtifactStore(context)

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val topic = args["topic"]?.trim().orEmpty()
        if (topic.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing topic for research request."))
        }

        return when (val result = researchRouter.research(topic)) {
            is ResearchRouter.ResearchResult.Success -> {
                val text = result.text.trim()
                artifactStore.saveResearchReport(
                    topic = topic,
                    title = "Research report: $topic",
                    text = text,
                    sourceLabel = result.model
                )
                Result.success(text)
            }
            is ResearchRouter.ResearchResult.ApiKeyMissing ->
                Result.failure(IllegalStateException("Research provider API key missing."))
            is ResearchRouter.ResearchResult.Error ->
                Result.failure(IllegalStateException(result.message))
        }
    }
}
