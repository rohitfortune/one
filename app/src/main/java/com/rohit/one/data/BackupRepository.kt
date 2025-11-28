package com.rohit.one.data

import android.content.Context
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class BackupRepository(private val context: Context) {

    private val client = OkHttpClient()
    private val moshi = Moshi.Builder().build()

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

        val metadataBody = RequestBody.create("application/json; charset=utf-8".toMediaType(), metadataJson)
        val fileBody = RequestBody.create("application/octet-stream".toMediaType(), encryptedPayload.toByteArray(Charsets.UTF_8))

        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", null, metadataBody)
            .addFormDataPart("file", "one_backup.json.enc", fileBody)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $accessToken")
            .post(multipart)
            .build()

        client.newCall(request).execute().use { resp ->
            return@withContext resp.isSuccessful
        }
    }

    // Download the latest backup file from appDataFolder. Caller must provide an access token.
    suspend fun downloadLatestBackupFromDrive(accessToken: String): String? = withContext(Dispatchers.IO) {
        val listUrl = "https://www.googleapis.com/drive/v3/files?q=name=%27one_backup.json.enc%27+and+parents+in+appDataFolder&spaces=appDataFolder&fields=files(id,name,createdTime)&orderBy=createdTime desc&pageSize=1"
        val listReq = Request.Builder()
            .url(listUrl)
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()

        client.newCall(listReq).execute().use { listResp ->
            if (!listResp.isSuccessful) return@withContext null
            val body = listResp.body?.string() ?: return@withContext null
            val jsonAdapter = moshi.adapter(Map::class.java)
            val parsed = jsonAdapter.fromJson(body) as? Map<*, *> ?: return@withContext null
            val files = parsed["files"] as? List<*> ?: return@withContext null
            if (files.isEmpty()) return@withContext null
            val first = files[0] as? Map<*, *> ?: return@withContext null
            val id = first["id"] as? String ?: return@withContext null

            val downloadUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            val dlReq = Request.Builder()
                .url(downloadUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            client.newCall(dlReq).execute().use { dlResp ->
                if (!dlResp.isSuccessful) return@withContext null
                return@withContext dlResp.body?.string()
            }
        }
    }
}
