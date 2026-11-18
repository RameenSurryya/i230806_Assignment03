package com.rameen.i230806.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.android.volley.DefaultRetryPolicy
import com.android.volley.NetworkResponse
import com.android.volley.NoConnectionError
import com.android.volley.ParseError
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.Response
import com.android.volley.TimeoutError
import com.android.volley.VolleyError
import com.android.volley.toolbox.HttpHeaderParser
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.RequestFuture
import com.android.volley.toolbox.Volley
import com.rameen.i230806.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Every call to our PHP web services goes through here (Volley).
 *
 *  - Base address from res/values/server.xml, e.g. http://192.168.100.68/kinnect/
 *  - Sends the session token from SessionStore as "Authorization: Bearer <token>".
 *  - Actions from the offline queue also send "X-Request-Id: <uuid>", so the server can
 *    ignore a repeat of the same action (see once() in backend/lib/api.php).
 *  - Answers are JSON: {"ok": true, ...} or {"ok": false, "error": "..."}.
 *
 * Two ways to call:
 *  - get / post / upload: for screens. The answer arrives later on the main thread.
 *  - postNow / uploadNow: wait for the answer. Only for background threads (the sync worker).
 *
 * KinnectApp calls init() once when the app starts.
 */
object Api {

    private const val TIMEOUT_MS = 20_000

    private lateinit var queue: RequestQueue
    private lateinit var baseUrl: String

    fun init(context: Context) {
        if (::queue.isInitialized) return
        val app = context.applicationContext
        queue = Volley.newRequestQueue(app)
        baseUrl = app.getString(R.string.server_url)
    }

    /** "posts/feed.php" -> "http://192.168.100.68/kinnect/posts/feed.php" */
    fun url(path: String): String = baseUrl + path

    /**
     * The answer to one request.
     *  - ok: the server said {"ok": true}; [json] holds the rest of the answer.
     *  - offline: the server couldn't be reached (no internet, laptop off). Queue and retry later.
     *  - otherwise: the server refused it; [error] says why (e.g. "Username taken"), [status] = HTTP code.
     */
    class Result(val json: JSONObject?, val status: Int, val error: String?, val offline: Boolean) {
        val ok: Boolean get() = json != null && json.optBoolean("ok")

        /** 401: the token was revoked or is unknown, the user has to log in again. */
        val loggedOut: Boolean get() = status == 401
    }

    /** A file in a multipart upload (a photo, a video, any file). */
    class FilePart(val field: String, val fileName: String, val mimeType: String, val bytes: ByteArray)

    // ---------------------------------------------------------------------------------------
    // For screens (answer on the main thread)
    // ---------------------------------------------------------------------------------------

    /** GET path?key=value&... */
    fun get(path: String, params: Map<String, Any?> = emptyMap(), onResult: (Result) -> Unit) {
        send(JsonRequest(
            Request.Method.GET, url(path) + query(params), null, null,
            { json -> onResult(Result(json, 200, null, false)) },
            { error -> onResult(failure(error)) },
        ))
    }

    /** POST with a JSON body. [requestId] = the action's UUID when it may be repeated. */
    fun post(path: String, body: JSONObject, requestId: String? = null, onResult: (Result) -> Unit) {
        send(JsonRequest(
            Request.Method.POST, url(path), body, requestId,
            { json -> onResult(Result(json, 200, null, false)) },
            { error -> onResult(failure(error)) },
        ))
    }

    /** POST multipart/form-data: text [fields] plus [files] (photos, videos, documents). */
    fun upload(
        path: String,
        fields: Map<String, String>,
        files: List<FilePart>,
        requestId: String? = null,
        onResult: (Result) -> Unit,
    ) {
        val request = MultipartRequest(
            url(path), fields, files, requestId,
            { json -> onResult(Result(json, 200, null, false)) },
            { error -> onResult(failure(error)) },
        )
        send(request)
    }

    // ---------------------------------------------------------------------------------------
    // For the sync worker (waits for the answer; never call these on the main thread)
    // ---------------------------------------------------------------------------------------

    fun postNow(path: String, body: JSONObject, requestId: String?): Result {
        val future = RequestFuture.newFuture<JSONObject>()
        send(JsonRequest(Request.Method.POST, url(path), body, requestId, future, future))
        return await(future)
    }

    fun uploadNow(path: String, fields: Map<String, String>, files: List<FilePart>, requestId: String?): Result {
        val future = RequestFuture.newFuture<JSONObject>()
        send(MultipartRequest(url(path), fields, files, requestId, future, future))
        return await(future)
    }

    private fun await(future: RequestFuture<JSONObject>): Result = try {
        Result(future.get(TIMEOUT_MS + 5_000L, TimeUnit.MILLISECONDS), 200, null, false)
    } catch (e: ExecutionException) {
        failure(e.cause as? VolleyError ?: VolleyError(e))
    } catch (e: Exception) {
        Result(null, 0, e.message, true)   // timed out waiting: treat as offline, retry later
    }

    // ---------------------------------------------------------------------------------------
    // Files from the phone
    // ---------------------------------------------------------------------------------------

    /** Reads a picked / captured file (content:// or file://) into an upload part. */
    fun filePart(context: Context, field: String, uri: Uri): FilePart? {
        return try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: guessMime(uri.toString())
            if (mime.startsWith("image/") && mime != "image/gif") {
                // Photos are shrunk first (longest side 1080 px, JPEG): small, fast uploads.
                val jpeg = com.rameen.i230806.util.Images.compress(context, uri)
                if (jpeg != null) {
                    val base = fileName(context, uri, mime).substringBeforeLast('.')
                    return FilePart(field, "$base.jpg", "image/jpeg", jpeg)
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            FilePart(field, fileName(context, uri, mime), mime, bytes)
        } catch (e: Exception) {
            null
        }
    }

    /** The file's real name ("report.pdf"); for camera files a name with the right extension. */
    private fun fileName(context: Context, uri: Uri, mime: String): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
        }
        val last = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        if (last.contains('.')) return last
        val extension = when {
            mime.startsWith("video") -> "mp4"
            mime == "image/png" -> "png"
            else -> "jpg"
        }
        return "file_${System.currentTimeMillis()}.$extension"
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "mp4" -> "video/mp4"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    // ---------------------------------------------------------------------------------------
    // Inside
    // ---------------------------------------------------------------------------------------

    private fun send(request: Request<*>) {
        // One try per call: the offline queue decides about retries, not Volley.
        request.retryPolicy = DefaultRetryPolicy(TIMEOUT_MS, 0, 1f)
        queue.add(request)
    }

    private fun headers(requestId: String?): Map<String, String> {
        val headers = HashMap<String, String>()
        SessionStore.token?.let { headers["Authorization"] = "Bearer $it" }
        requestId?.let { headers["X-Request-Id"] = it }
        return headers
    }

    private fun query(params: Map<String, Any?>): String {
        val parts = params.filterValues { it != null }.map { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value.toString(), "UTF-8")
        }
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }

    /** Turns a Volley error into a Result: offline, or the server's {"error": "..."} message. */
    private fun failure(error: VolleyError): Result {
        if (error is NoConnectionError || error is TimeoutError || error.networkResponse == null) {
            return Result(null, 0, "No connection to the server", true)
        }
        val response = error.networkResponse
        val message = try {
            JSONObject(String(response.data, Charsets.UTF_8)).optString("error")
        } catch (e: Exception) {
            null
        }
        return Result(null, response.statusCode, message?.ifEmpty { null } ?: "Server error ${response.statusCode}", false)
    }

    // ---------------------------------------------------------------------------------------
    // Request classes
    // ---------------------------------------------------------------------------------------

    /** Volley's JSON request, plus our headers (token and request id). */
    private class JsonRequest(
        method: Int,
        url: String,
        body: JSONObject?,
        private val requestId: String?,
        listener: Response.Listener<JSONObject>,
        errorListener: Response.ErrorListener,
    ) : JsonObjectRequest(method, url, body, listener, errorListener) {
        override fun getHeaders(): MutableMap<String, String> = HashMap(headers(requestId))
    }

    // ---------------------------------------------------------------------------------------
    // multipart/form-data (Volley has no built-in upload request)
    // ---------------------------------------------------------------------------------------

    private class MultipartRequest(
        url: String,
        private val fields: Map<String, String>,
        private val files: List<FilePart>,
        private val requestId: String?,
        private val listener: Response.Listener<JSONObject>,
        errorListener: Response.ErrorListener,
    ) : Request<JSONObject>(Request.Method.POST, url, errorListener) {

        private val boundary = "----KinnectBoundary" + System.currentTimeMillis()

        override fun getHeaders(): MutableMap<String, String> = HashMap(headers(requestId))

        override fun getBodyContentType() = "multipart/form-data; boundary=$boundary"

        /**
         * --boundary
         * Content-Disposition: form-data; name="text"
         *
         * Hello
         * --boundary
         * Content-Disposition: form-data; name="images[]"; filename="a.jpg"
         * Content-Type: image/jpeg
         *
         * <the file's bytes>
         * --boundary--
         */
        override fun getBody(): ByteArray {
            val out = ByteArrayOutputStream()
            fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))
            for ((name, value) in fields) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            for (file in files) {
                write("--$boundary\r\nContent-Disposition: form-data; name=\"${file.field}\"; filename=\"${file.fileName}\"\r\n")
                write("Content-Type: ${file.mimeType}\r\n\r\n")
                out.write(file.bytes)
                write("\r\n")
            }
            write("--$boundary--\r\n")
            return out.toByteArray()
        }

        override fun parseNetworkResponse(response: NetworkResponse): Response<JSONObject> = try {
            val text = String(response.data, Charsets.UTF_8)
            Response.success(JSONObject(text), HttpHeaderParser.parseCacheHeaders(response))
        } catch (e: Exception) {
            Response.error(ParseError(e))
        }

        override fun deliverResponse(response: JSONObject) = listener.onResponse(response)
    }
}
