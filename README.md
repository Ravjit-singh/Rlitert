```markdown
# R liteRT: Native Android Local AI Inference Engine

R liteRT is a high-performance, standalone background service designed to execute Large Language Models (LLMs) locally on Android hardware. It functions as an independent inference server, exposing an OpenAI-compatible REST API directly on the device's internal loopback network (localhost). 

This project bridges the gap between resource-heavy native Android development and lightweight frontend web technologies by utilizing a hybrid WebView UI coupled with a robust Kotlin background service. It is designed to act as the offline "brain" for local applications, terminal environments (like Termux), and custom dashboards.

---

## Core Architecture

R liteRT bypasses standard Android constraints by utilizing a decoupled architecture. The application is split into two distinct operational layers:

### 1. The Native Kotlin Service (EngineService.kt)
The backbone of the application is a persistent Android Foreground Service. 
* **NanoHTTPD Server:** A lightweight Java-based HTTP server runs natively on Port 8080, intercepting standard network requests from the device's internal network.
* **Google Edge LiteRT:** The inference engine utilizes Google's LiteRT framework to load model weights directly into device memory.
* **Hardware Acceleration Fallback:** The engine implements a progressive initialization sequence. It first attempts to bind the model to the device's GPU driver for maximum token-per-second throughput. If the GPU driver rejects the specific model graph or lacks memory, the system catches the exception and dynamically falls back to standard CPU inference without crashing.

### 2. The Native WebView Bridge (MainActivity.kt)
Instead of relying on heavy Jetpack Compose or XML layouts, the user interface is rendered using a borderless WebView executing standard HTML, JavaScript, and Tailwind CSS.
* **JavascriptInterface:** A secure bridge is established between the DOM and the Kotlin runtime. HTML buttons trigger native Android intents (such as requesting storage permissions, reading absolute file paths, and manually terminating background services to clear RAM).

---

## Dynamic Model Loading

To circumvent the Android Package (APK) size limits and allow seamless model swapping, R liteRT does not bundle any AI models within its compiled assets. 

The application utilizes a Dynamic File Scanner with the following strict parameters:
* **Target Directory:** `/storage/emulated/0/Download/RashboardModels/`
* **File Extension:** The engine recursively scans the directory for the first file matching the `.litertlm` extension (case-insensitive).
* **RAM Unloading:** The UI provides a manual hardware kill-switch. When invoked, it explicitly stops the Foreground Service and triggers the `close()` method on the LiteRT interpreter, instantly purging the model from RAM and allowing the device operating system to reclaim memory.

---

## System Requirements

To run this inference engine successfully, the target Android hardware must meet the following baseline specifications:

* **Operating System:** Android 8.0 (API Level 26) or higher.
* **Permissions:** * `android.permission.INTERNET`
  * `android.permission.READ_EXTERNAL_STORAGE`
  * `android.permission.MANAGE_EXTERNAL_STORAGE` (Required for Android 11+ to read large model weights).
* **Hardware:** Minimum 4GB of available device RAM is recommended for 2B parameter models (e.g., Gemma 2B). 
* **Storage:** Sufficient internal storage to house the `.litertlm` model file (typically 2GB - 3GB per model).

---

## Installation & Setup

For non-developers wishing to utilize the engine for their local environments:

1. Navigate to the **Releases** section of this repository.
2. Download the lightweight shell `R-liteRT-v1.x.x.apk`.
3. Install the application on your Android device (ensure "Install from Unknown Sources" is enabled).
4. Download a compatible LiteRT model (e.g., Gemma 4 E2B IT).
5. Using your device's file manager, create a folder named `RashboardModels` inside your `Downloads` directory.
6. Move the `.litertlm` model file into the `Downloads/RashboardModels/` directory.
7. Open the R liteRT application, grant the requested "All Files Access" permission, and tap **Initialize Protocol**.

---

## API Specification

Once the application reports "API ONLINE", the background service begins listening for POST requests on `127.0.0.1:8080`. 

The server is specifically formatted to act as a drop-in replacement for the OpenAI Chat Completions endpoint. This means any software capable of talking to OpenAI can talk to your Android phone offline.

### Endpoint
`POST http://127.0.0.1:8080/v1/chat/completions`

### Headers
* `Content-Type: application/json`
* `Access-Control-Allow-Origin: *` (CORS is globally permitted for local web-app access)

### Request Payload (JSON)
The server expects a standard JSON array of message objects containing `role` and `content` keys.

```json
{
  "messages": [
    {
      "role": "system",
      "content": "You are a helpful, localized AI assistant."
    },
    {
      "role": "user",
      "content": "Explain the architecture of a Native WebView Bridge."
    }
  ],
  "temperature": 0.7,
  "stream": true
}

```
### Response Format (Server-Sent Events)
To maintain high performance and low latency, the NanoHTTPD server pipes the LiteRT token generation directly into an SSE (Server-Sent Events) stream.
```text
data: {"choices":[{"delta":{"content":"A"}}]}
data: {"choices":[{"delta":{"content":" Native"}}]}
data: {"choices":[{"delta":{"content":" WebView"}}]}
data: {"choices":[{"delta":{"content":" Bridge"}}]}
data: {"choices":[{"delta":{"content":" connects"}}]}
...
data: [DONE]

```
## Developer Integration (Termux Example)
R liteRT is heavily optimized to run alongside Termux instances serving Node.js or Python backend applications.
If you are running a local dashboard on Node.js, you can route your LLM calls directly to the engine using standard fetch requests.
```javascript
// Node.js example calling R liteRT running in the background
async function callLocalEngine(userPrompt) {
    const response = await fetch('[http://127.0.0.1:8080/v1/chat/completions](http://127.0.0.1:8080/v1/chat/completions)', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            messages: [{ role: "user", content: userPrompt }]
        })
    });

    const text = await response.text();
    console.log("Local Engine Response:", text);
}

```
## Building from Source
For Android developers wishing to modify the NanoHTTPD routing logic or update the Google Edge SDK:
 1. Clone this repository locally.
 2. Open the project directory in Android Studio.
 3. Allow Gradle to sync dependencies.
 4. Ensure you do NOT bundle any model files in the src/main/assets/ directory to prevent APK bloat.
 5. Compile the debug APK using standard Gradle commands or the Android Studio UI:
   ./gradlew assembleDebug
 6. Retrieve the compiled APK from app/build/outputs/apk/debug/.
## Security Considerations
 * **Localhost Binding:** The HTTP server binds exclusively to 127.0.0.1. It does not broadcast the API endpoint across the external Wi-Fi network (0.0.0.0), ensuring that external devices on the same network cannot access or query the AI models running on your phone.
 * **Storage Isolation:** The application strictly looks for model weights inside Downloads/RashboardModels/. It does not traverse or scan the rest of the file system.
## Future Roadmap
 * Implement dynamic network binding (allow users to toggle 0.0.0.0 to expose the API to their local Wi-Fi network).
 * Support for parallel model loading.
 * Extended endpoint logic to support OpenAI's /v1/models route for auto-discovery in applications like LM Studio or AnythingLLM.
 * Add native UI monitoring for device temperature and real-time RAM allocation during generation.
## License
This project is licensed under the MIT License. See the LICENSE file for details.
```

```
