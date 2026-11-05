package com.example.narratethis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.Xml
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val GOOGLE_DOC = "application/vnd.google-apps.document"
const val WORD_DOC = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
const val PLAIN_TEXT = "text/plain"

data class DriveDoc(
    val id: String,
    val name: String,
    val modifiedTime: String,
    val mimeType: String = GOOGLE_DOC,
) {
    val kind: String
        get() = when (mimeType) {
            GOOGLE_DOC -> "Google Doc"
            WORD_DOC -> "Word file"
            else -> "Text file"
        }
}

/** Thrown when the user has to approve Drive access in a Google consent screen. */
class ConsentRequiredException(val pendingIntent: PendingIntent) : Exception("Google consent required")

class UnauthorizedException : IOException("Google access token rejected")

/**
 * Talks to Google Drive's REST API using an OAuth access token obtained through
 * Google Play services' AuthorizationClient (read-only Drive scope).
 */
class GoogleDrive(context: Context) {
    private val appContext = context.applicationContext
    private val authClient = Identity.getAuthorizationClient(appContext)
    private var accessToken: String? = null

    private val authRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
        .build()

    val isAuthorized: Boolean get() = accessToken != null

    /**
     * Gets an access token. If the user hasn't granted access yet, throws
     * [ConsentRequiredException] carrying the intent for the consent screen.
     */
    suspend fun authorize(): String {
        accessToken?.let { return it }
        val result = authClient.authorize(authRequest).await()
        return handleResult(result)
    }

    /** Call with the data returned from the consent screen. */
    fun completeAuthorization(data: Intent?) {
        handleResult(authClient.getAuthorizationResultFromIntent(data))
    }

    private fun handleResult(result: AuthorizationResult): String {
        if (result.hasResolution()) {
            throw ConsentRequiredException(result.pendingIntent!!)
        }
        Log.d(TAG, "Granted scopes: ${result.grantedScopes}")
        return result.accessToken?.also { accessToken = it }
            ?: throw IOException("Google did not return an access token")
    }

    /** The signed-in Google account's email, so the user can see which account is in use. */
    suspend fun accountEmail(): String? = runCatching {
        JSONObject(get("$API/about?fields=${enc("user(emailAddress)")}"))
            .getJSONObject("user").getString("emailAddress")
    }.onFailure { Log.w(TAG, "about failed", it) }.getOrNull()

    /**
     * Lists Google Docs, Word files and text files (including shared drives), newest first,
     * optionally filtered by a name search.
     */
    suspend fun listDocs(search: String = ""): List<DriveDoc> {
        val query = buildString {
            append("(mimeType='$GOOGLE_DOC' or mimeType='$WORD_DOC' or mimeType='$PLAIN_TEXT')")
            append(" and trashed=false")
            if (search.isNotBlank()) {
                append(" and name contains '")
                append(search.trim().replace("\\", "\\\\").replace("'", "\\'"))
                append("'")
            }
        }
        val url = "$API/files?q=${enc(query)}&orderBy=${enc("modifiedTime desc")}" +
            "&pageSize=100&fields=${enc("files(id,name,modifiedTime,mimeType)")}" +
            "&corpora=allDrives&includeItemsFromAllDrives=true&supportsAllDrives=true"
        val json = JSONObject(get(url))
        val files = json.getJSONArray("files")
        Log.d(TAG, "listDocs(\"$search\") returned ${files.length()} files")
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            DriveDoc(
                f.getString("id"), f.getString("name"), f.optString("modifiedTime"),
                f.optString("mimeType", GOOGLE_DOC),
            )
        }
    }

    /** Downloads a document's text: Google Docs are exported, Word files are unpacked. */
    suspend fun fetchText(doc: DriveDoc): String {
        val text = when (doc.mimeType) {
            GOOGLE_DOC -> String(getBytes("$API/files/${enc(doc.id)}/export?mimeType=${enc(PLAIN_TEXT)}"))
            WORD_DOC -> download(doc.id).let { withContext(Dispatchers.Default) { docxToText(it) } }
            else -> String(download(doc.id))
        }
        return text.removePrefix("﻿")
    }

    private suspend fun download(id: String) =
        getBytes("$API/files/${enc(id)}?alt=media&supportsAllDrives=true")

    private suspend fun get(url: String): String = String(getBytes(url))

    /** GET with the bearer token; on a 401 the token is refreshed once and the call retried. */
    private suspend fun getBytes(url: String): ByteArray {
        return try {
            httpGet(url, authorize())
        } catch (e: UnauthorizedException) {
            accessToken?.let { stale ->
                withContext(Dispatchers.IO) {
                    runCatching { GoogleAuthUtil.clearToken(appContext, stale) }
                }
            }
            accessToken = null
            httpGet(url, authorize())
        }
    }

    private suspend fun httpGet(url: String, token: String): ByteArray = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            when (val code = conn.responseCode) {
                in 200..299 -> conn.inputStream.use { it.readBytes() }
                401 -> throw UnauthorizedException()
                else -> {
                    val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w(TAG, "HTTP $code for $url: $body")
                    val message = runCatching {
                        JSONObject(body).getJSONObject("error").getString("message")
                    }.getOrNull()
                    throw IOException("Drive error $code${message?.let { ": $it" } ?: ""}")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "NarrateThis"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    }
}

/** Pulls the paragraphs of text out of a .docx file (word/document.xml). */
internal fun docxToText(bytes: ByteArray): String {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name != "word/document.xml") continue
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(zip, "UTF-8")
            val out = StringBuilder()
            var inText = false
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "w:t" -> inText = true
                        "w:tab" -> out.append(' ')
                        "w:br", "w:cr" -> out.append('\n')
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "w:t" -> inText = false
                        "w:p" -> out.append('\n')
                    }
                    XmlPullParser.TEXT -> if (inText) out.append(parser.text)
                }
            }
            return out.toString()
        }
    }
    throw IOException("This Word file has no readable text")
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
