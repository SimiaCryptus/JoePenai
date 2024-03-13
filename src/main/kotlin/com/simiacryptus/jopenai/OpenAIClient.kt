package com.simiacryptus.jopenai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.google.common.util.concurrent.ListeningScheduledExecutorService
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.simiacryptus.jopenai.ApiModel.*
import com.simiacryptus.jopenai.exceptions.ModerationException
import com.simiacryptus.jopenai.models.*
import com.simiacryptus.jopenai.util.ClientUtil.allowedCharset
import com.simiacryptus.jopenai.util.ClientUtil.checkError
import com.simiacryptus.jopenai.util.ClientUtil.defaultApiProvider
import com.simiacryptus.jopenai.util.ClientUtil.keyTxt
import com.simiacryptus.jopenai.util.JsonUtil
import com.simiacryptus.jopenai.util.StringUtil
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.client5.http.entity.mime.FileBody
import org.apache.hc.client5.http.entity.mime.HttpMultipartMode
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
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
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

open class OpenAIClient(
  protected var key: Map<APIProvider, String> = mapOf(defaultApiProvider to keyTxt),
  protected val apiBase: Map<APIProvider, String> = APIProvider.values().associate { it to (it.base ?: "") },
  logLevel: Level = Level.INFO,
  logStreams: MutableList<BufferedOutputStream> = mutableListOf(),
  scheduledPool: ListeningScheduledExecutorService = HttpClientManager.scheduledPool,
  workPool: ThreadPoolExecutor = HttpClientManager.workPool,
  client: CloseableHttpClient = HttpClientManager.client
) : HttpClientManager(
  logLevel = logLevel,
  logStreams = logStreams,
  scheduledPool = scheduledPool,
  workPool = workPool,
  client = client
) {

  private val tokenCounter = AtomicInteger(0)

  open fun onUsage(model: OpenAIModel?, tokens: Usage) {
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
  protected fun post(url: String, json: String, apiProvider: APIProvider): String {
    val request = HttpPost(url)
    request.addHeader("Content-Type", "application/json")
    request.addHeader("Accept", "application/json")
    authorize(request, apiProvider)
    request.entity = StringEntity(json, Charsets.UTF_8, false)
    return post(request)
  }

  protected fun post(request: HttpPost): String = withClient { EntityUtils.toString(it.execute(request).entity) }

  @Throws(IOException::class)
  protected open fun authorize(request: HttpRequest, apiProvider: APIProvider) {
    request.addHeader("Authorization", "Bearer ${key.get(apiProvider)}")
  }

  @Throws(IOException::class)
  protected operator fun get(url: String?, apiProvider: APIProvider): String = withClient {
    val request = HttpGet(url)
    request.addHeader("Content-Type", "application/json")
    request.addHeader("Accept", "application/json")
    authorize(request, apiProvider)
    EntityUtils.toString(it.execute(request).entity)
  }

  fun listEngines(): List<Engine> = JsonUtil.objectMapper().readValue(
    JsonUtil.objectMapper().readValue(
      get("${apiBase[defaultApiProvider]}/engines", defaultApiProvider), ObjectNode::class.java
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
        "${apiBase[defaultApiProvider]}/engines/${model.modelName}/completions", StringUtil.restrictCharacterSet(
          JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(request),
          allowedCharset
        ), defaultApiProvider
      )
      checkError(result)
      val response = JsonUtil.objectMapper().readValue(
        result, CompletionResponse::class.java
      )
      if (response.usage != null) {
        onUsage(model, response.usage.copy(cost = model.pricing(response.usage)))
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
      authorize(request, defaultApiProvider)
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

  open fun createSpeech(request: SpeechRequest): ByteArray? = withReliability {
    withPerformanceLogging {
      val httpRequest = HttpPost("${apiBase[defaultApiProvider]}/audio/speech")
      authorize(httpRequest, defaultApiProvider)
      httpRequest.addHeader("Accept", "application/json")
      httpRequest.addHeader("Content-Type", "application/json")
      httpRequest.entity = StringEntity(JsonUtil.objectMapper().writeValueAsString(request), Charsets.UTF_8, false)
      val response = withClient { it.execute(httpRequest).entity }
      val contentType = response.contentType
      val bytes = response.content.readAllBytes()
      if (contentType != null && contentType.startsWith("text") || contentType.startsWith("application/json")) {
        checkError(bytes.toString(Charsets.UTF_8))
        null
      } else {
        val model = AudioModels.values().find { it.modelName.equals(request.model, true) }
        onUsage(
          model, Usage(
            prompt_tokens = request.input.length,
            cost = model?.pricing(request.input.length)
          )
        )
        bytes
      }
    }
  }


  open fun render(prompt: String = "", resolution: Int = 1024, count: Int = 1): List<BufferedImage> =
    withReliability {
      withPerformanceLogging {
        renderCounter.incrementAndGet()
        val url = "${apiBase[defaultApiProvider]}/images/generations"
        val request = HttpPost(url)
        request.addHeader("Accept", "application/json")
        request.addHeader("Content-Type", "application/json")
        authorize(request, defaultApiProvider)
        val jsonObject = JsonObject()
        jsonObject.addProperty("prompt", prompt)
        jsonObject.addProperty("n", count)
        jsonObject.addProperty("size", "${resolution}x$resolution")
        request.entity = StringEntity(jsonObject.toString(), Charsets.UTF_8, false)
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
    chatRequest: ChatRequest, model: ChatModels
  ): ChatResponse {
    //log.info("Chat request: $chatRequest", RuntimeException())
    return withReliability {
      withPerformanceLogging {
        chatCounter.incrementAndGet()

        val apiProvider = model.provider
        val result = when {
          apiProvider == APIProvider.Perplexity -> {
            val json =
              JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(chatRequest.copy(stop = null))
            log(msg = String.format("Chat Request\nPrefix:\n\t%s\n", json.replace("\n", "\n\t")))
            post("${apiBase[apiProvider]}/chat/completions", json, apiProvider)
          }

          apiProvider == APIProvider.Groq -> {
            val json = JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(toGroq(chatRequest))
            log(msg = String.format("Chat Request\nPrefix:\n\t%s\n", json.replace("\n", "\n\t")))
            post("${apiBase[apiProvider]}/chat/completions", json, apiProvider)
          }

          apiProvider == APIProvider.ModelsLab -> {
            val json =
              JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(toModelsLab(chatRequest))
            log(msg = String.format("Chat Request\nPrefix:\n\t%s\n", json.replace("\n", "\n\t")))
            fromModelsLab(post("${apiBase[apiProvider]}/llm/chat", json, apiProvider))
          }

          else -> {
            val json = JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(chatRequest)
            log(msg = String.format("Chat Request\nPrefix:\n\t%s\n", json.replace("\n", "\n\t")))
            post("${apiBase[apiProvider]}/chat/completions", json, apiProvider)
          }
        }
        checkError(result)
        val response = JsonUtil.objectMapper().readValue(result, ChatResponse::class.java)
        if (response.usage != null) {
          onUsage(model, response.usage.copy(cost = model.pricing(response.usage)))
        }
        log(
          msg = String.format(
            "Chat Completion:\n\t%s",
            response.choices.firstOrNull()?.message?.content?.trim { it <= ' ' }?.replace("\n", "\n\t")
              ?: JsonUtil.toJson(response)
          )
        )
        response
      }
    }
  }

  private fun fromModelsLab(rawResponse: String): String {
    //log.info("Received response from ModelsLab: $rawResponse", RuntimeException())
    val response = JsonUtil.objectMapper().readValue(rawResponse, ModelsLabDataModel.ChatResponse::class.java)
    return when(response.status) {
      "success" -> {
        JsonUtil.toJson(ChatResponse(
          id = response.chat_id,
          choices = listOf(
            ChatChoice(
              message = ChatMessageResponse(content = response.message),
              index = 0
            )
          ),
          usage = response.meta?.let {
            Usage(
              prompt_tokens = it.max_new_tokens ?: 0,
              completion_tokens = 0, // Assuming no direct mapping; adjust as needed.
              total_tokens = it.max_new_tokens ?: 0
            )
          }
        ))
      }
      "processing" -> {
        val seconds = response?.eta ?: 1
        log.info("Chat response is still processing; waiting ${seconds}s and trying again.")
        Thread.sleep(seconds * 1000L)
        val postCheck = JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(
          mapOf(
            "chat_id" to (response.meta?.chat_id ?: response.chat_id),
            "key" to key[APIProvider.ModelsLab]
          )
        )
        fromModelsLab(post("${apiBase[defaultApiProvider]}/llm/get_queued_response", postCheck, defaultApiProvider))
      }
      "error" -> {
        throw RuntimeException("Error in chat request: ${response.message}\n$rawResponse")
      }
      "failed" -> {
        throw RuntimeException("Chat request failed: ${response.message}\n$rawResponse")
      }
      else -> throw RuntimeException("Unknown status: ${response.status}\n${response.message}\n$rawResponse")
    }
  }

  private fun toModelsLab(chatRequest: ApiModel.ChatRequest): ModelsLabDataModel.ChatRequest {
    val chatRequest1 = ModelsLabDataModel.ChatRequest(
      key = key[APIProvider.ModelsLab],
      model_id = chatRequest.model,
      system_prompt = chatRequest.messages.filter { it.role == Role.system }.joinToString("\n") {
        it.content?.joinToString("\n") { it.text ?: "" } ?: "" },
      prompt = chatRequest.messages.filter { it.role != Role.system }.joinToString("\n") {
        it.content?.joinToString("\n") { it.text ?: "" } ?: "" },
      max_new_tokens = 512,
      temperature = chatRequest.temperature,
//      do_sample = true,
//      top_k = 50,
//      top_p = 10.0,
      no_repeat_ngram_size = 5,
//      seed = 1235,
//      temp = false,
    )
    log.info("Converted chat request ${
      JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(chatRequest)
    } to ModelsLab format: ${
      JsonUtil.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(chatRequest1)
    }")
/*
*/
    return chatRequest1
  }

  private fun toGroq(chatRequest: ChatRequest): GroqChatRequest = GroqChatRequest(
    messages = chatRequest.messages.map { message ->
      GroqChatMessage(
        role = message.role,
        content = message.content?.joinToString("\n") { it.text ?: "" } ?: "",
      )
    },
    model = chatRequest.model,
    max_tokens = chatRequest.max_tokens,
    temperature = chatRequest.temperature,
  )

  open fun moderate(text: String) = withReliability {
    when {
      defaultApiProvider == APIProvider.Groq -> return@withReliability
      defaultApiProvider == APIProvider.ModelsLab -> return@withReliability
    }
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
        this.post("${apiBase[defaultApiProvider]}/moderations", body, defaultApiProvider)
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
            }.reduce { a: String, b: String -> "$a, $b" }.orElse("???")
          )
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
      val result = post("${apiBase[defaultApiProvider]}/edits", request, defaultApiProvider)
      checkError(result)
      val response = JsonUtil.objectMapper().readValue(
        result, CompletionResponse::class.java
      )
      if (response.usage != null) {
        val model = EditModels.values().values.find { it.modelName.equals(editRequest.model, true) }
        onUsage(
          model, response.usage.copy(cost = model?.pricing(response.usage))
        )
      }
      log(
        msg = String.format(
          "Chat Completion:\n\t%s",
          response.firstChoice.orElse("").toString().trim { it <= ' ' }.toString().replace("\n", "\n\t")
        )
      )
      response
    }
  }

  open fun listModels(): ModelListResponse {
    val result = get("${apiBase[defaultApiProvider]}/models", defaultApiProvider)
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
          "${apiBase[defaultApiProvider]}/embeddings", StringUtil.restrictCharacterSet(
            JsonUtil.objectMapper().writeValueAsString(request), allowedCharset
          ), defaultApiProvider
        )
        checkError(result)
        val response = JsonUtil.objectMapper().readValue(
          result, EmbeddingResponse::class.java
        )
        if (response.usage != null) {
          val model = EmbeddingModels.values().values.find { it.modelName.equals(request.model, true) }
          onUsage(
            model,
            response.usage.copy(cost = model?.pricing(response.usage))
          )
        }
        response
      }
    }
  }

  open fun createImage(request: ImageGenerationRequest): ImageGenerationResponse = withReliability {
    withPerformanceLogging {
      val url = "${apiBase[defaultApiProvider]}/images/generations"
      val httpRequest = HttpPost(url)
      httpRequest.addHeader("Accept", "application/json")
      httpRequest.addHeader("Content-Type", "application/json")
      authorize(httpRequest, defaultApiProvider)

      val requestBody = Gson().toJson(request)
      httpRequest.entity = StringEntity(requestBody, Charsets.UTF_8, false)

      val response = post(httpRequest)
      checkError(response)
      val model = ImageModels.values().find { it.modelName.equals(request.model, true) }
      val dims = request.size?.split("x")
      onUsage(
        model, Usage(
          completion_tokens = 1, cost = model?.pricing(
            width = dims?.get(0)?.toInt() ?: 0,
            height = dims?.get(1)?.toInt() ?: 0
          )
        )
      )

      JsonUtil.objectMapper().readValue(response, ImageGenerationResponse::class.java)
    }
  }

  open fun createImageEdit(request: ImageEditRequest): ImageEditResponse = withReliability {
    withPerformanceLogging {
      val url = "${apiBase[defaultApiProvider]}/images/edits"
      val httpRequest = HttpPost(url)
      httpRequest.addHeader("Accept", "application/json")
      authorize(httpRequest, defaultApiProvider)

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
      val url = "${apiBase[defaultApiProvider]}/images/variations"
      val httpRequest = HttpPost(url)
      httpRequest.addHeader("Accept", "application/json")
      authorize(httpRequest, defaultApiProvider)

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
