package com.simiacryptus.jopenai.opt

import com.simiacryptus.jopenai.ClientUtil.toContentList
import com.simiacryptus.jopenai.OpenAIClient
import com.simiacryptus.jopenai.describe.Description
import com.simiacryptus.jopenai.models.ChatModels
import com.simiacryptus.jopenai.models.OpenAITextModel
import com.simiacryptus.jopenai.opt.PromptOptimization.GeneticApi.Prompt
import com.simiacryptus.jopenai.proxy.ChatProxy
import org.slf4j.LoggerFactory
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

open class PromptOptimization(
    val api: OpenAIClient,
    val model: OpenAITextModel = ChatModels.GPT35Turbo,
    private val mutationRate: Double = 0.5,
    private val mutatonTypes: Map<String, Double> = mapOf(
        "Rephrase" to 1.0,
        "Randomize" to 1.0,
        "Summarize" to 1.0,
        "Expand" to 1.0,
        "Reorder" to 1.0,
        "Remove Duplicate" to 1.0,
    )
) {

    data class TestCase(val turns: List<Turn>, val retries: Int = 3)

    data class Turn(val userMessage: String, val expectations: List<Expectation>)

    open fun runGeneticGenerations(
        systemPrompts: List<String>,
        testCases: List<TestCase>,
        selectionSize: Int = max(ceil(ln((systemPrompts.size + 1).toDouble()) / ln(2.0)), 3.0)
            .toInt(), // apx ln(N)
        populationSize: Int = max(max(selectionSize, 5), systemPrompts.size),
        generations: Int = 3
    ): List<String> {
        var topPrompts = regenerate(systemPrompts, populationSize)
        for (generation in 0..generations) {
            val scores = topPrompts.map { prompt ->
                prompt to testCases.map { testCase ->
                    evaluate(prompt, testCase)
                }.average()
            }
            scores.sortedByDescending { it.second }.forEach {
                log.info("""Scored ${it.second}: ${it.first.replace("\n", "\\n")}""")
            }
            if (generation == generations) {
                log.info("Final generation: ${topPrompts.first()}")
                break
            } else {
                val survivors = scores.sortedByDescending { it.second }.take(selectionSize).map { it.first }
                topPrompts = regenerate(survivors, populationSize)
                log.info("Generation $generation: ${topPrompts.first()}")
            }
        }
        return topPrompts
    }

    open fun regenerate(progenetors: List<String>, desiredCount: Int): List<String> {
        val result = listOf<String>().toMutableList()
        result += progenetors
        while (result.size < desiredCount) {
            if (progenetors.size == 1) {
                val selected = progenetors.first()
                val mutated = mutate(selected)
                result += mutated
            } else if (progenetors.size == 0) {
                throw RuntimeException("No survivors")
            } else {
                val a = progenetors.random()
                var b: String
                do {
                    b = progenetors.random()
                } while (a == b)
                val child = recombine(a, b)
                result += child
            }
        }
        return result
    }

    open fun recombine(a: String, b: String): String {
        val temperature = 0.3
        for (retry in 0..3) {
            try {
                val child = geneticApi(temperature.pow(1.0 / (retry + 1))).recombine(Prompt(a), Prompt(b)).prompt
                if (child.contentEquals(a) || child.contentEquals(b)) {
                    log.info("Recombine failure: retry $retry")
                    continue
                }
                log.info(
                    "Recombined Prompts\n\t${
                        a.replace("\n", "\n\t")
                    }\n\t-- + --\n\t${
                        b.replace("\n", "\n\t")
                    }\n\t-- -> --\n\t${child.replace("\n", "\n\t")}"
                )
                if (Math.random() < mutationRate) {
                    return mutate(child)
                } else {
                    return child
                }
            } catch (e: Exception) {
                log.warn("Failed to recombine $a + $b", e)
            }
        }
        return mutate(a)
    }

    open fun mutate(selected: String): String {
        val temperature = 0.3
        for (retry in 0..10) {
            try {
                val directive = getMutationDirective()
                val mutated = geneticApi(temperature.pow(1.0 / (retry + 1))).mutate(Prompt(selected), directive).prompt
                if (mutated.contentEquals(selected)) {
                    log.info("Mutate failure $retry ($directive): ${selected.replace("\n", "\\n")}")
                    continue
                }
                log.info(
                    "Mutated Prompt\n\t${selected.replace("\n", "\n\t")}\n\t-- -> --\n\t${
                        mutated.replace(
                            "\n",
                            "\n\t"
                        )
                    }"
                )
                return mutated
            } catch (e: Exception) {
                log.warn("Failed to mutate $selected", e)
            }
        }
        throw RuntimeException("Failed to mutate $selected")
    }

    open fun getMutationDirective(): String {
        val fate = mutatonTypes.values.sum() * Math.random()
        var cumulative = 0.0
        for ((key, value) in mutatonTypes) {
            cumulative += value
            if (fate < cumulative) {
                return key
            }
        }
        return mutatonTypes.keys.random()
    }

    protected interface GeneticApi {
        @Description("Mutate the given prompt; rephrase, make random edits, etc.")
        fun mutate(
            systemPrompt: Prompt,
            directive: String = "Rephrase"
        ): Prompt

        @Description("Recombine the given prompts to produce a third with about the same length; swap phrases, reword, etc.")
        fun recombine(
            systemPromptA: Prompt,
            systemPromptB: Prompt
        ): Prompt

        data class Prompt(
            val prompt: String
        )
    }

    protected open fun geneticApi(temperature: Double = 0.3) = ChatProxy(
        clazz = GeneticApi::class.java,
        api = api,
        model = model,
        temperature = temperature
    ).create()

    open fun evaluate(systemPrompt: String, testCase: TestCase): Double {
        val steps = run(systemPrompt, testCase)
        return steps.map { it.second }.average()
    }

    open fun run(
        systemPrompt: String,
        testCase: TestCase
    ): List<Pair<com.simiacryptus.jopenai.ApiModel.ChatResponse, Double>> {
        var chatRequest = com.simiacryptus.jopenai.ApiModel.ChatRequest(
            model = model.modelName
        )
        var response = com.simiacryptus.jopenai.ApiModel.ChatResponse()
        chatRequest = chatRequest.copy(messages = chatRequest.messages + com.simiacryptus.jopenai.ApiModel.ChatMessage(
            com.simiacryptus.jopenai.ApiModel.Role.system,
            systemPrompt.toContentList()
        ))
        return testCase.turns.map { turn ->
            var matched: Boolean
            chatRequest = chatRequest.copy(messages = chatRequest.messages + com.simiacryptus.jopenai.ApiModel.ChatMessage(
                com.simiacryptus.jopenai.ApiModel.Role.user,
                turn.userMessage.toContentList()
            ))
            val startTemp = 0.3
            chatRequest = chatRequest.copy(temperature = startTemp)
            for (retry in 0..testCase.retries) {
                response = api.chat(chatRequest, model)
                matched = turn.expectations.all { it.matches(api, response) }
                if (matched) {
                    break
                } else {
                    chatRequest = chatRequest.copy(temperature = startTemp.coerceAtLeast(0.1).pow(1.0 / (retry + 1)))
                    log.info(
                        "Retry $retry (T=${"%.3f".format(chatRequest.temperature)}): ${
                            systemPrompt.replace(
                                "\n",
                                "\\n"
                            )
                        } / ${turn.userMessage}\n\t${response.choices.first().message?.content?.replace("\n", "\n\t")}"
                    )
                }
            }
            chatRequest = chatRequest.copy(messages = chatRequest.messages + com.simiacryptus.jopenai.ApiModel.ChatMessage(
                com.simiacryptus.jopenai.ApiModel.Role.assistant,
                (response.choices.first().message?.content ?: "").toContentList()
            ))
            response to turn.expectations.map { it.score(api, response) }.average()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PromptOptimization::class.java)
    }

}


