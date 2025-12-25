@file:Suppress("UNUSED_PARAMETER")
package com.rohit.one.data

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.net.Uri
import android.util.Base64

class BackupRepository(@Suppress("unused") private val context: Context) {

    private val client = OkHttpClient()
    // Use KotlinJsonAdapterFactory so Moshi can use generated adapters or fallback to reflection
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    suspend fun createEncryptedBackup(jsonPayload: String): String = withContext(Dispatchers.IO) {
        CryptoUtil.encrypt(jsonPayload)
    }

    suspend fun decryptBackup(encrypted: String): String? = withContext(Dispatchers.IO) {
        CryptoUtil.decrypt(encrypted)
    }

    suspend fun createPassphraseBackup(jsonPayload: String, passphrase: CharArray): String = withContext(Dispatchers.IO) {
        BackupCrypto.encryptWithPassphrase(jsonPayload, passphrase)
    }

    suspend fun decryptPassphraseBackup(encrypted: String, passphrase: CharArray): String? = withContext(Dispatchers.IO) {
        BackupCrypto.decryptWithPassphrase(encrypted, passphrase)
    }

    // Upload to Drive appData via simple REST call. Caller must supply a valid OAuth access token
    suspend fun uploadBackupToDrive(encryptedPayload: String, accessToken: String): Boolean = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id"
        val metadataJson = "{\"name\": \"one_backup.json.enc\", \"parents\": [\"appDataFolder\"]}"

        val metadataBody = metadataJson.toRequestBody("application/json; charset=utf-8".toMediaType())
        val fileBody = encryptedPayload.toByteArray(Charsets.UTF_8).toRequestBody("application/octet-stream".toMediaType())

        // Build multipart/related manually to ensure the Drive API receives the exact expected format
        try {
            val boundary = "----OneBoundary${System.currentTimeMillis()}"
            val newline = "\r\n"
            val metaPartHeader = "--$boundary$newline" +
                    "Content-Type: application/json; charset=UTF-8" + newline + newline
            val filePartHeader = "--$boundary$newline" +
                    "Content-Type: application/octet-stream" + newline + newline

            val endBoundary = "--$boundary--$newline"

            val baos = java.io.ByteArrayOutputStream()
            baos.write(metaPartHeader.toByteArray(Charsets.UTF_8))
            baos.write(metadataJson.toByteArray(Charsets.UTF_8))
            baos.write(newline.toByteArray(Charsets.UTF_8))
            baos.write(filePartHeader.toByteArray(Charsets.UTF_8))
            // write binary payload
            baos.write(encryptedPayload.toByteArray(Charsets.UTF_8))
            // ensure CRLF before the closing boundary
            baos.write(newline.toByteArray(Charsets.UTF_8))
            baos.write(endBoundary.toByteArray(Charsets.UTF_8))

            val multipartContentType = "multipart/related; boundary=$boundary".toMediaType()
            val bodyBytes = baos.toByteArray()
            try {
                val previewLen = kotlin.math.min(bodyBytes.size, 512)
                val preview = bodyBytes.copyOfRange(0, previewLen)
                val hexPreview = preview.joinToString(separator = " ") { String.format("%02x", it) }
                Log.d("BackupRepository", "Prepared multipart body: contentType=$multipartContentType length=${bodyBytes.size} preview(hex)=$hexPreview")
            } catch (e: Exception) {
                Log.w("BackupRepository", "Failed to generate multipart preview: ${e.message}")
            }
            val requestBody = baos.toByteArray().toRequestBody(multipartContentType)

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            Log.d("BackupRepository", "Uploading request with Content-Type=${request.header("Content-Type")}, contentLength=${requestBody.contentLength()}")

            client.newCall(request).execute().use { resp ->
                val bodyStr = try { resp.body?.string() } catch (e: Exception) { "<error reading body: ${e.message}>" }
                if (!resp.isSuccessful) {
                    Log.w("BackupRepository", "Upload failed: code=${resp.code} message=${resp.message} body=$bodyStr")
                    throw java.io.IOException("Drive upload failed: code=${resp.code} message=${resp.message} body=$bodyStr")
                } else {
                    Log.i("BackupRepository", "Upload succeeded: code=${resp.code} body=$bodyStr")
                    return@withContext true
                }
            }
         } catch (e: Exception) {
             Log.e("BackupRepository", "Multipart manual builder failed: ${e.message}", e)
             // Fallback to simple form-data (previous behavior) as last resort
             val multipartFallback = MultipartBody.Builder().setType(MultipartBody.FORM)
                 .addFormDataPart("metadata", null, metadataBody)
                 .addFormDataPart("file", "one_backup.json.enc", fileBody)
             val request = Request.Builder()
                 .url(url)
                 .addHeader("Authorization", "Bearer $accessToken")
                 .post(multipartFallback.build())
                 .build()

             client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string()
                Log.d("BackupRepository", "Upload fallback response: code=${resp.code} body=$bodyStr")
                if (!resp.isSuccessful) {
                    throw java.io.IOException("Drive upload fallback failed: code=${resp.code} body=$bodyStr")
                }
                return@withContext true
             }
         }
    }

    // Download the latest backup file from appDataFolder. Caller must provide an access token.
    suspend fun downloadLatestBackupFromDrive(accessToken: String): String? = withContext(Dispatchers.IO) {
        // Correct query: name='one_backup.json.enc' and 'appDataFolder' in parents
        val query = "name='one_backup.json.enc' and 'appDataFolder' in parents"
        val listUrl = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&spaces=appDataFolder&fields=files(id,name,createdTime)&orderBy=createdTime%20desc&pageSize=1"

        Log.d("BackupRepository", "Listing backups with URL: $listUrl")

        val listReq = Request.Builder()
            .url(listUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        client.newCall(listReq).execute().use { listResp ->
            if (!listResp.isSuccessful) {
                Log.w("BackupRepository", "List request failed: ${listResp.code} ${listResp.message}")
                return@withContext null
            }
            val body = listResp.body?.string() ?: run {
                Log.w("BackupRepository", "List response body empty")
                return@withContext null
            }
            Log.d("BackupRepository", "List response: $body")
            val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            val jsonAdapter: JsonAdapter<Map<String, Any>> = moshi.adapter(mapType)
            val parsed = jsonAdapter.fromJson(body) ?: run {
                Log.w("BackupRepository", "Failed to parse list response JSON")
                return@withContext null
            }
            val files = parsed["files"] as? List<*> ?: run {
                Log.d("BackupRepository", "No 'files' key in response")
                return@withContext null
            }
            if (files.isEmpty()) {
                Log.d("BackupRepository", "No backup files found in appDataFolder")
                return@withContext null
            }
            val first = files[0] as? Map<*, *> ?: run {
                Log.w("BackupRepository", "Unexpected file entry structure")
                return@withContext null
            }
            val id = first["id"] as? String ?: run {
                Log.w("BackupRepository", "No id found for file entry")
                return@withContext null
            }

            val downloadUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            val dlReq = Request.Builder()
                .url(downloadUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(dlReq).execute().use { dlResp ->
                if (!dlResp.isSuccessful) {
                    Log.w("BackupRepository", "Download failed: ${dlResp.code} ${dlResp.message}")
                    return@withContext null
                }
                val content = dlResp.body?.string()
                Log.d("BackupRepository", "Downloaded backup size=${content?.length ?: 0}")
                return@withContext content
            }
        }
    }

    // High-level helpers to be called from UI. These functions encapsulate building payloads,
    // encrypting, uploading and restoring. They accept callbacks to fetch raw secrets (passwords/cards)
    // because those require biometric-protected retrieval from the ViewModel layer.

    suspend fun performDeviceBackup(
        notes: List<Note>,
        passwords: List<Password>,
        cards: List<CreditCard>,
        fetchRawPassword: suspend (uuid: String) -> String?,
        fetchFullCardNumber: suspend (uuid: String) -> String?,
        accessTokenProvider: suspend () -> String?
    ): Boolean = withContext(Dispatchers.IO) {
         val pwExports = passwords.map { pw ->
             val raw = fetchRawPassword(pw.uuid)
             PasswordExport(pw.uuid, pw.title, pw.username, raw, pw.createdAt)
         }
         val cardExports = cards.map { c ->
             val full = fetchFullCardNumber(c.uuid)
             CardExport(c.uuid, c.cardholderName, c.last4, full, c.brand, c.expiry, c.securityCode, c.createdAt)
         }
         val noteExports = notes.map { n ->
             val attachmentExports = n.attachments.map { att ->
                 val base64 = try {
                     var bytes: ByteArray? = null
                     val file = java.io.File(att.uri)
                     
                     // Strategy 1: Reconstruct Path relative to current Context (safest)
                     if (att.uri.contains("files/attachments/")) {
                         try {
                              val filename = att.uri.substringAfterLast("/")
                              val cleanFile = java.io.File(java.io.File(context.filesDir, "attachments"), filename)
                              Log.d("BackupRepo", "Strategy 1: Reading clean file: ${cleanFile.absolutePath}")
                              if (cleanFile.exists()) {
                                  bytes = java.io.FileInputStream(cleanFile).use { it.readBytes() }
                              } else {
                                  Log.w("BackupRepo", "Strategy 1: File not found at clean path")
                              }
                         } catch (e: Exception) {
                              Log.w("BackupRepo", "Strategy 1 failed: ${e.message}")
                         }
                     }
                     
                     // Strategy 2: Direct File Read (old raw path)
                     if (bytes == null && att.uri.startsWith("/") && java.io.File(att.uri).exists()) {
                         try {
                              Log.d("BackupRepo", "Strategy 2: Reading raw file: ${att.uri}")
                              bytes = java.io.FileInputStream(java.io.File(att.uri)).use { it.readBytes() }
                         } catch (e: Exception) {
                              Log.w("BackupRepo", "Strategy 2 failed: ${e.message}")
                         }
                     }

                     // Strategy 3: Parsing as URI
                     if (bytes == null) {
                         try {
                              Log.d("BackupRepo", "Strategy 3: Reading parsed URI: ${att.uri}")
                              bytes = context.contentResolver.openInputStream(Uri.parse(att.uri))?.use { it.readBytes() }
                         } catch (e: Exception) {
                              Log.w("BackupRepo", "Strategy 3 failed: ${e.message}")
                         }
                     }

                     if (bytes != null && bytes.isNotEmpty()) {
                         Log.d("BackupRepo", "Success! Read ${bytes.size} bytes")
                         Base64.encodeToString(bytes, Base64.DEFAULT)
                     } else {
                         Log.e("BackupRepo", "ALL STRATEGIES FAILED or EMPTY FILE for ${att.uri}")
                         null
                     }
                 } catch (e: Exception) {
                     Log.e("BackupRepo", "Fatal error reading attachment ${att.uri}", e)
                     null
                 }
                 AttachmentExport(att.uri, att.displayName, att.mimeType, base64)
             }
             NoteExport(n.title, n.content, attachmentExports, n.paths)
         }
         val payload = BackupPayload(noteExports, pwExports, cardExports)
         val json = moshi.adapter(BackupPayload::class.java).toJson(payload)
         val enc = CryptoUtil.encrypt(json)
        // Try upload, and on auth failure attempt one refresh+retry using the provider
        val firstToken = try { accessTokenProvider() } catch (e: Exception) {
            Log.w("BackupRepository", "accessTokenProvider threw: ${e.message}")
            null
        }
        if (firstToken.isNullOrBlank()) {
            Log.w("BackupRepository", "No access token available for backup")
            return@withContext false
        } else {
            val preview = try { firstToken.take(6) + "..." + firstToken.takeLast(6) } catch (_: Exception) { "<hidden>" }
            Log.d("BackupRepository", "Using token for upload (preview)=$preview length=${firstToken.length}")
        }
        try {
            return@withContext uploadBackupToDrive(enc, firstToken)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            Log.w("BackupRepository", "Upload attempt failed: $msg")
            if (msg.contains("code=401") || msg.contains("UNAUTHENTICATED") || msg.contains("Invalid Credentials") || msg.contains("401")) {
                Log.w("BackupRepository", "Auth failure detected during upload; attempting one refresh+retry: ${e.message}")
                val refreshed = try { accessTokenProvider() } catch (ex: Exception) {
                    Log.w("BackupRepository", "accessTokenProvider refresh threw: ${ex.message}")
                    null
                }
                if (refreshed.isNullOrBlank()) {
                    Log.w("BackupRepository", "Token refresh failed or returned null")
                    return@withContext false
                }
                try {
                    val preview = try { refreshed.take(6) + "..." + refreshed.takeLast(6) } catch (_: Exception) { "<hidden>" }
                    Log.d("BackupRepository", "Retrying upload with refreshed token (preview)=$preview length=${refreshed.length}")
                    return@withContext uploadBackupToDrive(enc, refreshed)
                } catch (e2: Exception) {
                    Log.e("BackupRepository", "Retry after refresh failed: ${e2.message}")
                    return@withContext false
                }
            }
            throw e
        }
     }

    suspend fun performPassphraseBackup(
        notes: List<Note>,
        passwords: List<Password>,
        cards: List<CreditCard>,
        fetchRawPassword: suspend (uuid: String) -> String?,
        fetchFullCardNumber: suspend (uuid: String) -> String?,
        passphrase: CharArray,
        accessTokenProvider: suspend () -> String?
    ): Boolean = withContext(Dispatchers.IO) {
         val pwExports = passwords.map { pw ->
             val raw = fetchRawPassword(pw.uuid)
             PasswordExport(pw.uuid, pw.title, pw.username, raw, pw.createdAt)
         }
         val cardExports = cards.map { c ->
             val full = fetchFullCardNumber(c.uuid)
             CardExport(c.uuid, c.cardholderName, c.last4, full, c.brand, c.expiry, c.securityCode, c.createdAt)
         }
         val noteExports = notes.map { n ->
             val attachmentExports = n.attachments.map { att ->
                 val base64 = try {
                     var bytes: ByteArray? = null
                     val file = java.io.File(att.uri)
                     
                     // Strategy 1: Reconstruct Path relative to current Context
                     if (att.uri.contains("files/attachments/")) {
                         try {
                              val filename = att.uri.substringAfterLast("/")
                              val cleanFile = java.io.File(java.io.File(context.filesDir, "attachments"), filename)
                              if (cleanFile.exists()) {
                                  bytes = java.io.FileInputStream(cleanFile).use { it.readBytes() }
                              }
                         } catch (e: Exception) { Log.w("BackupRepo", "PP-Strategy 1 failed: ${e.message}") }
                     }

                     // Strategy 2: Direct File Read
                     if (bytes == null && att.uri.startsWith("/") && java.io.File(att.uri).exists()) {
                         try {
                              bytes = java.io.FileInputStream(java.io.File(att.uri)).use { it.readBytes() }
                         } catch (e: Exception) { Log.w("BackupRepo", "PP-Strategy 2 failed: ${e.message}") }
                     }

                     // Strategy 3: Parsing as URI
                     if (bytes == null) {
                         try {
                              bytes = context.contentResolver.openInputStream(Uri.parse(att.uri))?.use { it.readBytes() }
                         } catch (e: Exception) { Log.w("BackupRepo", "PP-Strategy 3 failed: ${e.message}") }
                     }

                     if (bytes != null && bytes.isNotEmpty()) {
                         Base64.encodeToString(bytes, Base64.DEFAULT)
                     } else {
                         Log.e("BackupRepo", "PP-ALL STRATEGIES FAILED for ${att.uri}")
                         null
                     }
                 } catch (e: Exception) {
                     Log.e("BackupRepo", "PP-Fatal error reading attachment ${att.uri}", e)
                     null
                 }
                 AttachmentExport(att.uri, att.displayName, att.mimeType, base64)
             }
             NoteExport(n.title, n.content, attachmentExports, n.paths)
         }
         val payload = BackupPayload(noteExports, pwExports, cardExports)
         val json = moshi.adapter(BackupPayload::class.java).toJson(payload)
         val enc = BackupCrypto.encryptWithPassphrase(json, passphrase)
        val firstToken = try { accessTokenProvider() } catch (_: Exception) { null }
        if (firstToken.isNullOrBlank()) {
            Log.w("BackupRepository", "No access token available for passphrase backup")
            return@withContext false
        }
        try {
            return@withContext uploadBackupToDrive(enc, firstToken)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("code=401") || msg.contains("UNAUTHENTICATED") || msg.contains("Invalid Credentials") || msg.contains("401")) {
                Log.w("BackupRepository", "Auth failure detected during passphrase upload; attempting one refresh+retry: ${e.message}")
                val refreshed = try { accessTokenProvider() } catch (_: Exception) { null }
                if (refreshed.isNullOrBlank()) {
                    Log.w("BackupRepository", "Token refresh failed or returned null")
                    return@withContext false
                }
                try {
                    return@withContext uploadBackupToDrive(enc, refreshed)
                } catch (e2: Exception) {
                    Log.e("BackupRepository", "Retry after refresh failed: ${e2.message}")
                    return@withContext false
                }
            }
            throw e
        }
     }

    suspend fun performDeviceRestore(
        accessTokenProvider: suspend () -> String?,
    ): String? = withContext(Dispatchers.IO) {
        val firstToken = try { accessTokenProvider() } catch (_: Exception) { null }
        if (firstToken.isNullOrBlank()) return@withContext null
        try {
            val enc = downloadLatestBackupFromDrive(firstToken) ?: return@withContext null
            return@withContext decryptBackup(enc)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("code=401") || msg.contains("UNAUTHENTICATED") || msg.contains("Invalid Credentials") || msg.contains("401")) {
                val refreshed = try { accessTokenProvider() } catch (_: Exception) { null }
                if (refreshed.isNullOrBlank()) return@withContext null
                val enc2 = try { downloadLatestBackupFromDrive(refreshed) } catch (_: Exception) { null } ?: return@withContext null
                return@withContext decryptBackup(enc2)
            }
            throw e
        }
    }

    suspend fun performPassphraseRestore(
        accessTokenProvider: suspend () -> String?,
        passphrase: CharArray
    ): String? = withContext(Dispatchers.IO) {
        val firstToken = try { accessTokenProvider() } catch (_: Exception) { null }
        if (firstToken.isNullOrBlank()) return@withContext null
        try {
            val enc = downloadLatestBackupFromDrive(firstToken) ?: return@withContext null
            return@withContext decryptPassphraseBackup(enc, passphrase)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            if (msg.contains("code=401") || msg.contains("UNAUTHENTICATED") || msg.contains("Invalid Credentials") || msg.contains("401")) {
                val refreshed = try { accessTokenProvider() } catch (_: Exception) { null }
                if (refreshed.isNullOrBlank()) return@withContext null
                val enc2 = try { downloadLatestBackupFromDrive(refreshed) } catch (_: Exception) { null } ?: return@withContext null
                return@withContext decryptPassphraseBackup(enc2, passphrase)
            }
            throw e
        }
    }

    // Return the createdTime of the latest backup file in appDataFolder (ISO 8601 string), or null if none
    suspend fun getLatestBackupCreatedTime(accessTokenProvider: suspend () -> String?): String? = withContext(Dispatchers.IO) {
        val token = try { accessTokenProvider() } catch (_: Exception) { null }
        if (token.isNullOrBlank()) return@withContext null

        try {
            val query = "name='one_backup.json.enc' and 'appDataFolder' in parents"
            val listUrl = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&spaces=appDataFolder&fields=files(id,name,createdTime)&orderBy=createdTime%20desc&pageSize=1"

            val listReq = Request.Builder()
                .url(listUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            client.newCall(listReq).execute().use { listResp ->
                if (!listResp.isSuccessful) {
                    Log.w("BackupRepository", "getLatestBackupCreatedTime list request failed: ${listResp.code} ${listResp.message}")
                    return@withContext null
                }
                val body = listResp.body?.string() ?: return@withContext null
                val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
                val jsonAdapter: JsonAdapter<Map<String, Any>> = moshi.adapter(mapType)
                val parsed = jsonAdapter.fromJson(body) ?: return@withContext null
                val files = parsed["files"] as? List<*> ?: return@withContext null
                if (files.isEmpty()) return@withContext null
                val first = files[0] as? Map<*, *> ?: return@withContext null
                val created = first["createdTime"] as? String
                return@withContext created
            }
        } catch (e: Exception) {
            Log.w("BackupRepository", "getLatestBackupCreatedTime failed: ${e.message}")
            return@withContext null
        }
    }
}
