package com.simiacryptus.jopenai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.simiacryptus.jopenai.ApiModel.*
import com.simiacryptus.jopenai.ClientUtil.allowedCharset
import com.simiacryptus.jopenai.ClientUtil.keyTxt
import com.simiacryptus.jopenai.exceptions.ModerationException
import com.simiacryptus.jopenai.models.*
import com.simiacryptus.jopenai.util.JsonUtil
import com.simiacryptus.jopenai.util.StringUtil
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.entity.mime.FileBody
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder
import org.apache.hc.core5.http.ContentType
import org.apache.hc.core5.http.HttpRequest
import org.apache.hc.core5.http.io.entity.EntityUtils
import org.apache.hc.core5.http.io.entity.StringEntity
import org.slf4j.event.Level
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.URL
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

open class OpenAIClient(
    protected var key: String = keyTxt,
    private val apiBase: String = "https://api.openai.com/v1",
    logLevel: Level = Level.INFO,
    logStreams: MutableList<BufferedOutputStream> = mutableListOf()
) : HttpClientManager(logLevel, logStreams), API {

    private val tokenCounter = AtomicInteger(0)

    open fun incrementTokens(model: OpenAIModel?, tokens: Usage) {
        tokenCounter.addAndGet(tokens.total_tokens)
    }

    open val metrics: Map<String, Any>
        get() = hashMapOf(
            "tokens" to tokenCounter.get(),
            "chats" to chatCounter.get(),
            "completions" to completionCounter.get(),
            "moderations" to moderationCounter.get(),
            "renders" to renderCounter.get(),
            "transcriptions" to transcriptionCounter.get(),
            "edits" to editCounter.get(),
        )
    protected val chatCounter = AtomicInteger(0)
    protected val completionCounter = AtomicInteger(0)
    protected val moderationCounter = AtomicInteger(0)
    protected val renderCounter = AtomicInteger(0)
    protected val transcriptionCounter = AtomicInteger(0)
    protected val editCounter = AtomicInteger(0)

    @Throws(IOException::class, InterruptedException::class)
    protected fun post(url: String, json: String): String {
        val request = HttpPost(url)
        request.addHeader("Content-Type", "application/json")
        request.addHeader("Accept", "application/json")
        authorize(request)
        request.entity = StringEntity(json)
        return post(request)
    }

    protected fun post(request: HttpPost): String = withClient { EntityUtils.toString(it.execute(request).entity) }

    @Throws(IOException::class)
    protected open fun authorize(request: HttpRequest) {
        request.addHeader("Authorization", "Bearer $key")
    }

    @Throws(IOException::class)
    protected operator fun get(url: String?): String = withClient {
        val request = HttpGet(url)
        request.addHeader("Content-Type", "application/json")
        request.addHeader("Accept", "application/json")
        authorize(request)
        EntityUtils.toString(it.execute(request).entity)
    }

    private fun checkError(result: String) {
        ClientUtil.checkError(result)
    }

    fun listEngines(): List<Engine> = JsonUtil.objectMapper().readValue(
        JsonUtil.objectMapper().readValue(
            get("$apiBase/engines"), ObjectNode::class.java
        )["data"]?.toString() ?: "{}", JsonUtil.objectMapper().typeFactory.constructCollectionType(
            List::class.java, Engine::class.java
        )
    )

    fun getEngineIds(): Array<CharSequence?> = listEngines().map { it.id }.sortedBy { it }.toTypedArray()

    open fun complete(
        request: CompletionRequest, model: OpenAITextModel
    ): CompletionResponse = withReliability {
        withPerformanceLogging {
            completionCounter.incrementAndGet()
            if (request.suffix == null) {
                log(
                    msg = String.format(
                        "Text Completion Request\nPrefix:\n\t%s\n", request.prompt.replace("\n", "\n\t")
                    )
                )
            } else {
                log(
                    msg = String.format(
                        "Text Completion Request\nPrefix:\n\t%s\nSuffix:\n\t%s\n",
                        request.prompt.replace("\n", "\n\t"),
                        request.suffix.replace("\n", "\n\t")
                    )
                )
            }
            val result = post(
                "$apiBase/engines/${model.modelName}/completions", StringUtil.restrictCharacterSet(
                    JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(request),
                    allowedCharset
                )
            )
            checkError(result)
            val response = JsonUtil.objectMapper().readValue(
                result, CompletionResponse::class.java
            )
            if (response.usage != null) {
                incrementTokens(model, response.usage)
            }
            val completionResult =
                StringUtil.stripPrefix(response.firstChoice.orElse("").toString().trim { it <= ' ' },
                    request.prompt.trim { it <= ' ' })
            log(
                msg = String.format(
                    "Chat Completion:\n\t%s", completionResult.toString().replace("\n", "\n\t")
                )
            )
            response
        }
    }

    open fun transcription(wavAudio: ByteArray, prompt: String = ""): String = withReliability {
        withPerformanceLogging {
            transcriptionCounter.incrementAndGet()
            val url = "$apiBase/audio/transcriptions"
            val request = HttpPost(url)
            request.addHeader("Accept", "application/json")
            authorize(request)
            val entity = MultipartEntityBuilder.create()
            entity.setMode(HttpMultipartMode.EXTENDED)
            entity.addBinaryBody("file", wavAudio, ContentType.create("audio/x-wav"), "audio.wav")
            entity.addTextBody("model", "whisper-1")
            entity.addTextBody("response_format", "verbose_json")
            if (prompt.isNotEmpty()) entity.addTextBody("prompt", prompt)
            request.entity = entity.build()
            val response = post(request)
            val jsonObject = Gson().fromJson(response, JsonObject::class.java)
            if (jsonObject.has("error")) {
                val errorObject = jsonObject.getAsJsonObject("error")
                throw RuntimeException(IOException(errorObject["message"].asString))
            }
            try {
                val result = JsonUtil.objectMapper().readValue(response, TranscriptionResult::class.java)
                result.text ?: ""
            } catch (e: Exception) {
                jsonObject.get("text").asString ?: ""
            }
        }
    }

    open fun createSpeech(request: SpeechRequest) : ByteArray? = withReliability {
        withPerformanceLogging {
            val httpRequest = HttpPost("$apiBase/audio/speech")
            authorize(httpRequest)
            httpRequest.addHeader("Accept", "application/json")
            httpRequest.addHeader("Content-Type", "application/json")
            httpRequest.entity = StringEntity(JsonUtil.objectMapper().writeValueAsString(request))
            val response = withClient { it.execute(httpRequest).entity }
            val contentType = response.contentType
            if(contentType != null && contentType.startsWith("text") || contentType.startsWith("application/json")) {
                checkError(response.content.readAllBytes().toString(Charsets.UTF_8))
                null
            } else {
                response.content.readAllBytes()
            }
        }
    }

    open fun render(prompt: String = "", resolution: Int = 1024, count: Int = 1): List<BufferedImage> =
        withReliability {
            withPerformanceLogging {
                renderCounter.incrementAndGet()
                val url = "$apiBase/images/generations"
                val request = HttpPost(url)
                request.addHeader("Accept", "application/json")
                request.addHeader("Content-Type", "application/json")
                authorize(request)
                val jsonObject = JsonObject()
                jsonObject.addProperty("prompt", prompt)
                jsonObject.addProperty("n", count)
                jsonObject.addProperty("size", "${resolution}x$resolution")
                request.entity = StringEntity(jsonObject.toString())
                val response = post(request)
                val jsonObject2 = Gson().fromJson(response, JsonObject::class.java)
                if (jsonObject2.has("error")) {
                    val errorObject = jsonObject2.getAsJsonObject("error")
                    throw RuntimeException(IOException(errorObject["message"].asString))
                }
                val dataArray = jsonObject2.getAsJsonArray("data")
                val images = ArrayList<BufferedImage>()
                for (i in 0 until dataArray.size()) {
                    images.add(ImageIO.read(URL(dataArray[i].asJsonObject.get("url").asString)))
                }
                images
            }
        }

    open fun chat(
        chatRequest: ChatRequest, model: OpenAITextModel
    ): ChatResponse =         withReliability {
        withPerformanceLogging {
            chatCounter.incrementAndGet()
            val reqJson =
                JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(chatRequest)
            log(
                msg = String.format(
                    "Chat Request\nPrefix:\n\t%s\n", reqJson.replace("\n", "\n\t")
                )
            )

            val jsonRequest = JsonUtil.objectMapper().writeValueAsString(chatRequest)
            val result = post("$apiBase/chat/completions", jsonRequest)
            checkError(result)
            val response = JsonUtil.objectMapper().readValue(result, ChatResponse::class.java)
            if (response.usage != null) {
                incrementTokens(model, response.usage)
            }
            log(msg = String.format("Chat Completion:\n\t%s",
                response.choices.firstOrNull()?.message?.content?.trim { it <= ' ' }?.replace("\n", "\n\t")
                    ?: JsonUtil.toJson(response)))
            response
        }
    }

    open fun moderate(text: String) = withReliability {
        withPerformanceLogging {
            moderationCounter.incrementAndGet()
            val body: String = try {
                JsonUtil.objectMapper().writeValueAsString(
                    mapOf(
                        "input" to StringUtil.restrictCharacterSet(text, allowedCharset)
                    )
                )
            } catch (e: JsonProcessingException) {
                throw RuntimeException(e)
            }
            val result: String = try {
                this.post("$apiBase/moderations", body)
            } catch (e: IOException) {
                throw RuntimeException(e)
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
            val jsonObject = Gson().fromJson(
                result, JsonObject::class.java
            ) ?: return@withPerformanceLogging
            if (jsonObject.has("error")) {
                val errorObject = jsonObject.getAsJsonObject("error")
                throw RuntimeException(IOException(errorObject["message"].asString))
            }
            val moderationResult = jsonObject.getAsJsonArray("results")[0].asJsonObject
            if (moderationResult["flagged"].asBoolean) {
                val categoriesObj = moderationResult["categories"].asJsonObject
                throw RuntimeException(
                    ModerationException("Moderation flagged this request due to " + categoriesObj.keySet()
                    .stream().filter { c: String? ->
                        categoriesObj[c].asBoolean
                    }.reduce { a: String, b: String -> "$a, $b" }.orElse("???"))
                )
            }
        }
    }

    open fun edit(
        editRequest: EditRequest
    ): CompletionResponse = withReliability {
        withPerformanceLogging {
            editCounter.incrementAndGet()
            if (editRequest.input == null) {
                log(
                    msg = String.format(
                        "Text Edit Request\nInstruction:\n\t%s\n", editRequest.instruction.replace("\n", "\n\t")
                    )
                )
            } else {
                log(
                    msg = String.format(
                        "Text Edit Request\nInstruction:\n\t%s\nInput:\n\t%s\n",
                        editRequest.instruction.replace("\n", "\n\t"),
                        editRequest.input.replace("\n", "\n\t")
                    )
                )
            }
            val request: String = StringUtil.restrictCharacterSet(
                JsonUtil.objectMapper().writeValueAsString(editRequest), allowedCharset
            )
            val result = post("$apiBase/edits", request)
            checkError(result)
            val response = JsonUtil.objectMapper().readValue(
                result, CompletionResponse::class.java
            )
            if (response.usage != null) {
                incrementTokens(
                    EditModels.values().find { it.modelName.equals(editRequest.model, true) }, response.usage
                )
            }
            log(msg = String.format("Chat Completion:\n\t%s",
                response.firstChoice.orElse("").toString().trim { it <= ' ' }.toString().replace("\n", "\n\t")))
            response
        }
    }

    open fun listModels(): ModelListResponse {
        val result = get("$apiBase/models")
        checkError(result)
        return JsonUtil.objectMapper().readValue(result, ModelListResponse::class.java)
    }

    open fun createEmbedding(
        request: EmbeddingRequest
    ): EmbeddingResponse {
        return withReliability {
            withPerformanceLogging {
                if (request.input is String) {
                    log(
                        msg = String.format(
                            "Embedding Creation Request\nModel:\n\t%s\nInput:\n\t%s\n",
                            request.model,
                            request.input.replace("\n", "\n\t")
                        )
                    )
                }
                val result = post(
                    "$apiBase/embeddings", StringUtil.restrictCharacterSet(
                        JsonUtil.objectMapper().writeValueAsString(request), allowedCharset
                    )
                )
                checkError(result)
                val response = JsonUtil.objectMapper().readValue(
                    result, EmbeddingResponse::class.java
                )
                if (response.usage != null) {
                    incrementTokens(
                        EmbeddingModels.values().find { it.modelName.equals(request.model, true) }, response.usage
                    )
                }
                response
            }
        }
    }

    open fun createImage(request: ImageGenerationRequest): ImageGenerationResponse = withReliability {
        withPerformanceLogging {
            val url = "$apiBase/images/generations"
            val httpRequest = HttpPost(url)
            httpRequest.addHeader("Accept", "application/json")
            httpRequest.addHeader("Content-Type", "application/json")
            authorize(httpRequest)

            val requestBody = Gson().toJson(request)
            httpRequest.entity = StringEntity(requestBody)

            val response = post(httpRequest)
            checkError(response)

            JsonUtil.objectMapper().readValue(response, ImageGenerationResponse::class.java)
        }
    }

    open fun createImageEdit(request: ImageEditRequest): ImageEditResponse = withReliability {
        withPerformanceLogging {
            val url = "$apiBase/images/edits"
            val httpRequest = HttpPost(url)
            httpRequest.addHeader("Accept", "application/json")
            authorize(httpRequest)

            val entityBuilder = MultipartEntityBuilder.create()
            entityBuilder.addPart("image", FileBody(request.image))
            entityBuilder.addTextBody("prompt", request.prompt)
            request.mask?.let { entityBuilder.addPart("mask", FileBody(it)) }
            request.model?.let { entityBuilder.addTextBody("model", it) }
            request.n?.let { entityBuilder.addTextBody("n", it.toString()) }
            request.size?.let { entityBuilder.addTextBody("size", it) }
            request.responseFormat?.let { entityBuilder.addTextBody("response_format", it) }
            request.user?.let { entityBuilder.addTextBody("user", it) }

            httpRequest.entity = entityBuilder.build()
            val response = post(httpRequest)
            checkError(response)

            JsonUtil.objectMapper().readValue(response, ImageEditResponse::class.java)
        }
    }

    open fun createImageVariation(request: ImageVariationRequest): ImageVariationResponse = withReliability {
        withPerformanceLogging {
            val url = "$apiBase/images/variations"
            val httpRequest = HttpPost(url)
            httpRequest.addHeader("Accept", "application/json")
            authorize(httpRequest)

            val entityBuilder = MultipartEntityBuilder.create()
            entityBuilder.addPart("image", FileBody(request.image))
            //request.model?.let { entityBuilder.addTextBody("model", it) }
            request.n?.let { entityBuilder.addTextBody("n", it.toString()) }
            request.responseFormat?.let { entityBuilder.addTextBody("response_format", it) }
            request.size?.let { entityBuilder.addTextBody("size", it) }
            request.user?.let { entityBuilder.addTextBody("user", it) }

            httpRequest.entity = entityBuilder.build()
            val response = post(httpRequest)
            checkError(response)

            JsonUtil.objectMapper().readValue(response, ImageVariationResponse::class.java)
        }
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(OpenAIClient::class.java)
    }

}
