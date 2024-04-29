# JoePenai - Unofficial Open Source OpenAI API Client for Java/Kotlin

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

This library is an _**unofficial**_ open source Java client for the OpenAI API, built with Kotlin and provided under the Apache 2.0 liscense.
It provides a simple interface for interacting with OpenAI's API, allowing access to text completions, edits, chats, transcriptions, and renderings.
Additionally, it provides some utilties for handling Audio for capturing transcriptions.
It also provides a GPT-Proxy API, which allows any Java/Kotlin interface to be serviced by a GPT model.

<!-- TOC -->
* [JoePenai - Unofficial Open Source OpenAI API Client for Java/Kotlin](#joepenai---unofficial-open-source-openai-api-client-for-javakotlin)
  * [To Import](#to-import)
  * [Basic Features](#basic-features)
    * [listEngines](#listengines)
    * [getEngineIds](#getengineids)
    * [complete](#complete)
    * [transcription](#transcription)
    * [render](#render)
    * [chat](#chat)
    * [moderate](#moderate)
    * [edit](#edit)
    * [listModels](#listmodels)
    * [createEmbedding](#createembedding)
  * [GPT-Proxy API](#gpt-proxy-api)
    * [Key Features](#key-features)
    * [Getting Started](#getting-started)
    * [Examples](#examples)
      * [Example 1: Simple Interface](#example-1-simple-interface)
      * [Example 2: Adding Metadata and Annotations](#example-2-adding-metadata-and-annotations)
      * [Example 3: Using Kotlin Data Classes](#example-3-using-kotlin-data-classes)
      * [Example 4: Text Parsing](#example-4-text-parsing)
      * [Example 5: Adding Examples](#example-5-adding-examples)
      * [Example 6: Adding Validation Rules](#example-6-adding-validation-rules)
    * [Best Practices](#best-practices)
    * [Configuration](#configuration)
    * [Internal Details](#internal-details)
    * [Troubleshooting](#troubleshooting)
<!-- TOC -->

## To Import

https://mvnrepository.com/artifact/com.simiacryptus/jo-penai

Maven:
```xml
<dependency>
    <groupId>com.simiacryptus</groupId>
    <artifactId>jo-penai</artifactId>
    <version>1.0.55</version>
</dependency>
```

Gradle:
```groovy
implementation group: 'com.simiacryptus', name: 'jo-penai', version: '1.0.55'
```

```kotlin
implementation("com.simiacryptus:jo-penai:1.0.55")
```


## Basic Features

All main features of the OpenAI API are supported, including:

| Feature            | Description                                                                                    |
|--------------------|------------------------------------------------------------------------------------------------|
| Text Completion    | Generate text completions from a prompt, predicting what comes next in a given context         |
| Text Editing       | Generate text edits from a prompt, making modifications to improve or change the original text |
| Text Chat          | Generate text responses to a prompt, simulating a conversation with the AI                     |
| Text Transcription | Generate text transcriptions from audio, converting spoken language into written text          |
| Text Rendering     | Generate images from a prompt, creating visual representations based on text descriptions      |

To use the `OpenAIClient`, you need to provide an API key and optionally an API base URL and log level.

```kotlin
import com.simiacryptus.jopenai.OpenAIClient

val apiKey = "your_api_key"
val apiBase = "https://api.openai.com/v1"
val logLevel = Level.INFO

val client = OpenAIClient(apiKey, apiBase, logLevel)
```

### listEngines

Returns a list of available engines.

```kotlin
val engines = client.listEngines()
```

### getEngineIds

Returns an array of engine IDs.

```kotlin
val engineIds = client.getEngineIds()
```

### complete

Performs a text completion using the specified model.

```kotlin
val request = com.simiacryptus.jopenai.ApiModel.CompletionRequest(prompt = "Once upon a time")
val model = Models.GPT35Turbo
val response = client.complete(request, model)
```

### transcription

Transcribes audio data in WAV format.

```kotlin
val wavAudio: ByteArray = ...
val transcription = client.transcription(wavAudio)
```

### render

Generates images based on a prompt.

```kotlin
val prompt = "A beautiful sunset over the ocean"
val resolution = 1024
val count = 1
val images = client.render(prompt, resolution, count)
```

### chat

Performs a chat completion.

```kotlin
val chatRequest = com.simiacryptus.jopenai.ApiModel.ChatRequest(/*...*/)
val chatResponse = client.chat(chatRequest)
```

### moderate

Moderates a given text.

```kotlin
val text = "Some text to moderate"
client.moderate(text)
```

### edit

Performs a text edit.

```kotlin
val editRequest = com.simiacryptus.jopenai.ApiModel.EditRequest(/*...*/)
val editResponse = client.edit(editRequest)
```

### listModels

Lists available models.

```kotlin
val modelListResponse = client.listModels()
```

### createEmbedding

Creates an embedding for a given input.

```kotlin
val embeddingRequest = com.simiacryptus.jopenai.ApiModel.EmbeddingRequest(/*...*/)
val embeddingResponse = client.createEmbedding(embeddingRequest)
```

## GPT-Proxy API

Built on top of the OpenAI API, JoePenai also provides a Proxy interface.
This allows any Java/Kotlin interface to be serviced by a GPT model.
It improves the chat api by adding type structures and metadata to both the request and response.
It allows description annotations, example requests/responses, and response validation to be added to the interface.
For example, you can easily parse plaintext into complex data structures by simply by providing a requested structure
and text.

With the GPT-Proxy API, you can now enjoy a more structured and efficient way of interacting with GPT models. This
powerful tool enhances your API experience by providing clear and consistent data types, metadata, and annotations. Say
goodbye to the hassle of prompt engineering and hello to scalability and improved content generation. Get started today
and unlock the full potential of the GPT-Proxy API!

### Key Features

1. **Strongly typed requests and responses** - Improve the clarity and consistency of your API by defining the expected
   input and output types.
2. **Metadata and annotations** - Add descriptions, examples, and validation rules to your interface, making it easier
   for
   developers to understand and use your API effectively.
3. **Automatic prompt generation** - Simplify prompt engineering by automatically generating prompts based on the
   provided
   interface and annotations.
4. **Scalability** - Manage multiple requests and data subsets efficiently, enabling higher-scale content generation and
   structured parsing.

### Getting Started

To start using the GPT-Proxy API, follow these steps:

1. Define your Java/Kotlin interface with the desired input and output types.
2. Create the proxy class using the GPT-Proxy API, specifying the interface and any necessary configuration.
3. Test your new API, and iteratively improve the names, annotations, examples, and validation rules.
4. ???
5. Profit!

### Examples

Here are some examples of how to use the GPT-Proxy API:

#### Example 1: Simple Interface

Define a simple interface for generating a summary of a given text:

```kotlin
interface TextSummarizer {
    fun summarize(text: String): String
}
```

Create the proxy class and use it:

```kotlin
val gptProxy = ChatProxy(TextSummarizer::class.java, System.getenv("OPENAI_KEY"))
val summarizer = gptProxy.create()
val summary = summarizer.summarize("Your long text goes here.")
println(summary)
```

This returned for me:

```json
{
  "summary": "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed sed mi vel libero aliquet feugiat sit amet faucibus enim. Sed vel velit commodo, lobortis leo vitae, lobortis nisi. Donec sollicitudin nunc a pretium bibendum. Fusce ac dui vel lectus facilisis commodo in vel justo. Sed at dapibus dolor, quis euismod mi. Sed eget rhoncus urna, vel cursus velit. Sed ac nunc in ex dictum ornare. Sed vitae rutrum nisi. Nullam sit amet nisi id urna dapibus iaculis. Sed malesuada, nisl a lacinia dignissim, ligula nisi maximus ligula, sed convallis libero sapien vel nulla. Curabitur auctor est in urna suscipit, et vehicula sapien varius. Sed convallis arcu sit amet quam bibendum, ut facilisis felis congue. Nullam ac magna id sapien blandit faucibus et sed magna. Integer vel mi eget odio luctus faucibus."
}
```

Notice; even though a string was requested, the response was a JSON object with a `summary` field.
Such is the nature of the GPT model, be warned!

#### Example 2: Adding Metadata and Annotations

Define a more complex interface with metadata and annotations:

```kotlin
interface BlogPostGenerator {
    @Description("Generate a blog post based on the given title and keywords.")
    fun generateBlogPost(
        @Description("The title of the blog post.") title: String,
        @Description("A list of keywords related to the blog post.") keywords: List<String>
    ): String
}
```

Create the proxy class and use it:

```kotlin
val gptProxy = ChatProxy(BlogPostGenerator::class.java, System.getenv("OPENAI_KEY"))
val generator = gptProxy.create()
val blogPost = generator.generateBlogPost(
    "The Future of AI",
    listOf("artificial intelligence", "machine learning", "deep learning")
)
println(blogPost)
```

This returned for me:

```text
Here is a sample response:

"The Future of AI

Artificial Intelligence (AI) is quickly becoming one of the most important technological advancements of our time. With the rise of machine learning and deep learning, AI is poised to revolutionize the way we work, live, and interact with each other.

Advances in AI have already led to significant improvements in fields such as healthcare, finance, and transportation. As AI continues to evolve, we can expect to see even more exciting developments in the years ahead.

So what does the future of AI hold? Some experts predict that we will see even more sophisticated AI systems that are capable of performing increasingly complex tasks. Others believe that AI will eventually surpass human intelligence, leading to a world where machines are in control.

Regardless of what the future holds, it is clear that AI will play a major role in shaping the world of tomorrow. As we continue to explore the possibilities of this remarkable technology, we can look forward to a future that is more efficient, more productive, and more innovative than ever before."
```

Notice, again, requesting a single string type results in GPT tending to "frame" the response.
When using a strongly typed response, I have found this to be less of an issue.

#### Example 3: Using Kotlin Data Classes

Define an interface using Kotlin data classes for input and output types:

```kotlin
data class RecipeInput(
    val title: String = "",
    val ingredients: List<String> = listOf(),
    val servings: Int = 0
)

data class RecipeOutput(
    val title: String = "",
    val ingredients: Map<String,String> = mapOf(),
    val instructions: List<String> = listOf(),
    val servings: Int = 0
)

interface RecipeGenerator {
    fun generateRecipe(input: RecipeInput): RecipeOutput
}
```

Create the proxy class and use it:

```kotlin
val gptProxy = ChatProxy(RecipeGenerator::class.java, System.getenv("OPENAI_KEY"))
val generator = gptProxy.create()
val recipeInput = RecipeInput("Chocolate Cake", listOf("flour", "sugar", "cocoa powder", "eggs", "milk"), 8)
val recipeOutput = generator.generateRecipe(recipeInput)
println(recipeOutput)
```

This returns a reliably good no-nonsense response:

```json
{
  "ingredients": {
    "flour": "200g",
    "sugar": "150g",
    "cocoa powder": "50g",
    "eggs": "2",
    "milk": "250ml"
  },
  "instructions": [
    "Preheat the oven to 180°C.",
    "Grease a 20cm round cake tin and line with baking paper.",
    "Sift the flour and cocoa powder into a large mixing bowl.",
    "Add the sugar and mix well.",
    "In a separate bowl, whisk together the eggs and milk.",
    "Pour the egg mixture into the dry ingredients and mix until well combined.",
    "Pour the batter into the prepared cake tin and bake for 30-35 minutes or until a skewer inserted into the centre comes out clean.",
    "Allow the cake to cool in the tin for 10 minutes before transferring to a wire rack to cool completely.",
    "Serve and enjoy!"
  ],
  "servings": 8,
  "title": "Chocolate Cake"
}
```

#### Example 4: Text Parsing

Define an interface for parsing a plain text shopping list into a structured data format:

```kotlin
data class ShoppingItem(
    var name: String = "",
    var quantity: Int = 0
)

interface ShoppingListParser {
    @Description("Parse a plain text shopping list into a structured data format.")
    fun parseShoppingList(@Description("The plain text shopping list.") text: String): List<ShoppingItem>
}
```

Create the proxy class and use it:

```kotlin
val gptProxy = ChatProxy(ShoppingListParser::class.java, System.getenv("OPENAI_KEY"))
val parser = gptProxy.create()
val plainTextList = "2 apples\n1 loaf of bread\n3 cans of soup\n4 bananas"
val parsedList = parser.parseShoppingList(plainTextList)
println(parsedList)
```

This returns for me:

```json
{
  "items": {
    "apples": 2,
    "loaf of bread": 1,
    "cans of soup": 3,
    "bananas": 4
  }
}
```

#### Example 5: Adding Examples

Define an interface:

```kotlin
data class WeatherInput(
    @Description("The city for which to get the weather forecast.") val city: String = "",
    @Description("The number of days for the forecast.") val days: Int = 0
)

data class WeatherOutput(
    @Description("The city for which the forecast is provided.") val city: String = "",
    @Description("The weather forecast for the specified number of days.") val forecast: List<String> = listOf()
)

interface WeatherForecast {
    @Description("Get a weather forecast for a specified city and number of days.")
    fun getWeatherForecast(input: WeatherInput): WeatherOutput
}
```

Create the proxy class, add examples, and use it:

```kotlin
val gptProxy = ChatProxy(WeatherForecast::class.java, System.getenv("OPENAI_KEY"))
gptProxy.addExample(WeatherOutput("New York", listOf("Sunny", "Partly Cloudy", "Rainy"))) {
    it.getWeatherForecast(WeatherInput("New York", 3))
}
val weather = gptProxy.create()
val weatherInput = WeatherInput("Seattle", 3)
val weatherOutput = weather.getWeatherForecast(weatherInput)
println(weatherOutput)
```

This returns for me:

```json
{
  "city": "Seattle",
  "forecast": [
    "Rainy",
    "Rainy",
    "Cloudy"
  ]
}
```

#### Example 6: Adding Validation Rules

Validation rules are important for defining what an "acceptable" response looks like.
This helps manage your API and take advantage of the client's retry and temperature management features.

Define an interface with validation rules:

```kotlin
data class MathProblem(
    @Description("The math problem to solve.") val problem: String = ""
)

data class MathSolution(
    @Description("The solution to the math problem.") val solution: String = ""
) : ValidatedObject {
    override fun validate(): Boolean {
        if (solution.isEmpty()) return false
        return super.validate()
    }
}

interface MathSolver {
    @Description("Solve a given math problem.")
    @ValidateResponse("response.solution.isNotEmpty()")
    fun solveMathProblem(input: MathProblem): MathSolution
}
```

Create the proxy class and use it:

```kotlin
val gptProxy = ChatProxy(MathSolver::class.java, System.getenv("OPENAI_KEY"))
val solver = gptProxy.create()
val mathInput = MathProblem("twelve pigs plus six cows minus four pigs")
val mathOutput = solver.solveMathProblem(mathInput)
println(mathOutput)
```

This returns for me, with ChatGPT:

```json
{
  "solution": "ten pigs plus six cows"
}
```

GPT4, configured like this:

```kotlin
if (!keyFile.exists()) return
val gptProxy = ChatProxy(MathSolver::class.java, apiKey)
gptProxy.model = "gpt-4-0314"
val solver = gptProxy.create()
val mathInput = MathProblem("twelve pigs plus six cows minus four pigs")
val mathOutput = solver.solveMathProblem(mathInput)
println(mathOutput)
```

Returned:

```json
{
  "solution": "8 pigs + 6 cows"
}
```

In this example, the validation rule ensures that the response contains a non-empty solution.
If the GPT model returns an empty solution or an invalid response,
the API will automatically retry the request with adjusted parameters (e.g., higher temperature)
until it receives a valid response or reaches the maximum number of retries.

By incorporating validation rules, you can improve the reliability and quality of the responses generated by the
GPT-Proxy API. This helps ensure that your application receives accurate and useful information from the GPT model,
ultimately enhancing the user experience.

Remember to follow best practices and iteratively test and improve your interface for optimal results. With the
GPT-Proxy API, you can unlock the full potential of GPT models and create powerful, structured, and scalable
applications.

These examples demonstrate the versatility and ease of use of the GPT-Proxy API. By following best practices and
leveraging the power of Kotlin data classes, metadata, and annotations, you can create powerful and efficient APIs for a
wide range of applications.

### Best Practices

When using the GPT-Proxy API, there are a few best practices to keep in mind:

1. Defining Input and Output Types
   1. Use Kotlin data classes for input and output types.
   2. Ensure all fields have default values and are mutable.
   3. Avoid accessor methods (e.g., get and set).
2. Retaining Parameter Metadata
   1. Configure your build system to retain parameter metadata.
   2. For Gradle, include compileKotlin { kotlinOptions { javaParameters = true }}.
3. Handling Collection Return Types
   1. Avoid collection return types.
   2. Use a wrapper object (e.g., MyWidgets) with an extra data class instead of List<MyWidget> as the return type.
4. Interface Design
   1. Keep interfaces simple and consistent.
   2. Provide enough information without causing confusion.
5. API Documentation
   1. Use descriptive names and annotations for better understanding.
   2. Utilize the @Description annotation to describe data structures.
6. Iterative Testing and Adjustments
   1. Test the API iteratively and refine the interface based on feedback.
   2. Analyze malformed responses and identify their causes.
7. Adding Examples
   1. Use the addExample call to provide example requests and responses.
8. Performance Monitoring
   1. Monitor API performance using the metrics method.


### Configuration

Configuration
When creating an instance of the GPT-Proxy API, there are several configuration options you can use to fine-tune the
behavior and performance of the proxy. Here are some of the most commonly used options:

1. **API Key**: The API key for your OpenAI account. This is required to authenticate your requests to the OpenAI API.
2. **Model**: The name of the GPT model you want to use (e.g., "gpt-3.5-turbo"). By default, the proxy will use the latest
   GPT model available.
3. **Temperature**: Controls the randomness of the generated text. A higher temperature (e.g., 0.8) will produce more
   creative and diverse responses, while a lower temperature (e.g., 0.2) will produce more focused and consistent
   responses. The default temperature is 0.4.
4. **Max tokens**: The maximum number of tokens (words and punctuation) in the generated response. By default, the proxy
   will use the maximum token limit of the selected GPT model.
5. **Max retries**: The maximum number of retries when a request fails due to validation errors or other issues. By default,
   the proxy will retry up to 3 times before giving up.
6. **Validation**: Enable or disable response validation. When enabled, the proxy will use the validation rules defined in
   your interface to check the quality of the generated responses. If a response does not pass the validation, the proxy
   will retry the request with an adjusted temperature. By default, validation is enabled.

Here is an example of how to create a GPT-Proxy instance with custom configuration options:

```kotlin
val gptProxy = ChatProxy(ShoppingListParser::class.java, System.getenv("OPENAI_KEY"))
gptProxy.model = "gpt-3.5-turbo"
gptProxy.temperature = 0.6
gptProxy.maxTokens = 100
gptProxy.maxRetries = 5
gptProxy.validation = true
```

### Internal Details

How does the GPT-Proxy API work? The GPT-Proxy API uses a combination of reflection and annotation processing to
generate
a proxy class that implements the desired interface. The proxy class is then used to interact with the GPT model.

When a method is called on the proxy class, the following steps are performed:

1. The method call is converted to a chat request
    1. The method signature is converted into an OpenAPI-like YAML description
    2. Any examples for this method are serialized into JSON
    3. Method arguments are converted into a JSON object
    4. These are combined into a Chat request
2. The call is made and the response is parsed into a JSON object
3. If there is a parsing or validation error, the request is retried, possibly with a higher temperature
4. The JSON object is converted into the desired output type and returned

### Troubleshooting

If you encounter issues while using the GPT-Proxy API, consider the following troubleshooting steps:

1. **Check your API key**: Ensure that you are using a valid API key and have the necessary permissions to access the
   GPT model.

2. **Check deserializer errors**: A common issue is forgetting default values for fields in Kotlin data classes. If you
   are using Kotlin data classes, make sure that all fields have default values and are mutable.

3. **Examine the error message**: Carefully read the error message to identify the root cause of the issue. The error
   message may provide valuable information about what went wrong and how to fix it.

4. **Review your interface**: Make sure your interface is well-defined and follows best practices. Ensure that you are
   using Kotlin data classes with default values and mutable fields, and that your build system retains parameter
   metadata.

5. **Inspect your annotations**: Check that you have used the appropriate annotations, such as `@Description`, to
   provide clear and accurate information about your API.

6. **Make sure parameter metadata is retained**: Make sure your build system retains parameter metadata. For example,
   your gradle build should include `compileKotlin { kotlinOptions { javaParameters = true }}`

7. **Test your API iteratively**: Continuously test your API and make adjustments as needed. Pay attention to malformed
   responses and examine why they are occurring.

8. **Monitor API performance**: Use the metrics method to monitor your API's performance and identify any potential
   bottlenecks or issues.

