/*
 *  ---license-start
 *  eu-digital-green-certificates / dgca-verifier-app-android
 *  ---
 *  Copyright (C) 2021 T-Systems International GmbH and all other contributors
 *  ---
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  ---license-end
 *
 *  Created by mykhailo.nester on 4/24/21 2:16 PM
 */

package it.ministerodellasalute.verificaC19sdk.data

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import dgca.verifier.app.decoder.base64ToX509Certificate
import dgca.verifier.app.decoder.toBase64
import it.ministerodellasalute.verificaC19sdk.data.local.AppDatabase
import it.ministerodellasalute.verificaC19sdk.data.local.Key
import it.ministerodellasalute.verificaC19sdk.data.local.Preferences
import it.ministerodellasalute.verificaC19sdk.data.remote.ApiService
import it.ministerodellasalute.verificaC19sdk.data.remote.model.CertificateRevocationList
import it.ministerodellasalute.verificaC19sdk.data.remote.model.CrlStatus
import it.ministerodellasalute.verificaC19sdk.data.remote.model.Rule
import it.ministerodellasalute.verificaC19sdk.di.DispatcherProvider
import it.ministerodellasalute.verificaC19sdk.security.KeyStoreCryptor
import java.lang.Exception
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.security.cert.CRL
import java.security.cert.Certificate
import javax.inject.Inject


class VerifierRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val preferences: Preferences,
    private val db: AppDatabase,
    private val keyStoreCryptor: KeyStoreCryptor,
    private val dispatcherProvider: DispatcherProvider
) : BaseRepository(dispatcherProvider), VerifierRepository {

    private val validCertList = mutableListOf<String>()
    private val fetchStatus: MutableLiveData<Boolean> = MutableLiveData()

    override suspend fun syncData(): Boolean? {
        return execute {
            fetchStatus.postValue(true)

            fetchValidationRules()
            getCRLStatus()

            if (fetchCertificates() == false) {
                fetchStatus.postValue(false)
                return@execute false
            }

            fetchStatus.postValue(false)
            return@execute true
        }
    }

    private suspend fun fetchValidationRules() {
        val response = apiService.getValidationRules()
        val body = response.body() ?: run {
            return
        }
        preferences.validationRulesJson = body.stringSuspending(dispatcherProvider)
    }

    private suspend fun fetchCertificates(): Boolean? {
        return execute {

            val response = apiService.getCertStatus()
            val body = response.body() ?: run {
                return@execute false
            }
            validCertList.clear()
            validCertList.addAll(body)

            if (body.isEmpty()) {
                preferences.resumeToken = -1L
            }

            val resumeToken = preferences.resumeToken
            fetchCertificate(resumeToken)
            db.keyDao().deleteAllExcept(validCertList.toTypedArray())

            //if db is empty for a reason, refresh sharedprefs and DB
            val recordCount = db.keyDao().getCount()
            Log.i("record count", recordCount.toString())
            if (recordCount.equals(0))
            {
                preferences.clear()
                this.syncData()
            }

            preferences.dateLastFetch = System.currentTimeMillis()

            return@execute true
        }
    }

    override suspend fun getCertificate(kid: String): Certificate? {
        val key = db.keyDao().getById(kid)
        return if (key != null) keyStoreCryptor.decrypt(key.key)!!
            .base64ToX509Certificate() else null
    }

    override fun getCertificateFetchStatus(): LiveData<Boolean> {
        return fetchStatus
    }

    private suspend fun fetchCertificate(resumeToken: Long) {
        val tokenFormatted = if (resumeToken == -1L) "" else resumeToken.toString()
        val response = apiService.getCertUpdate(tokenFormatted)

        if (response.isSuccessful && response.code() == HttpURLConnection.HTTP_OK) {
            val headers = response.headers()
            val responseKid = headers[HEADER_KID]
            val newResumeToken = headers[HEADER_RESUME_TOKEN]
            val responseStr = response.body()?.stringSuspending(dispatcherProvider) ?: return

            if (validCertList.contains(responseKid)) {
                Log.i(VerifierRepositoryImpl::class.java.simpleName, "Cert KID verified")
                val key = Key(kid = responseKid!!, key = keyStoreCryptor.encrypt(responseStr)!!)
                db.keyDao().insert(key)

                preferences.resumeToken = resumeToken

                newResumeToken?.let {
                    val newToken = it.toLong()
                    fetchCertificate(newToken)
                }
            }
        }
    }

    private suspend fun getCRLStatus() {
        val response = apiService.getCRLStatus(preferences.fromVersion)
        val body = response.body() ?: run {
        }
        var crlstatus: CrlStatus = Gson().fromJson(response.body()?.string(), CrlStatus::class.java)
        Log.i("CRL Status", crlstatus.toString())

        //todo check if server crl version is newer than app crl version
        //then update
        //todo initialize lastDownloadedVersion
        if (preferences.lastDownloadedVersion < crlstatus.version) {
            //preferences.fromVersion = crlstatus.fromVersion
            preferences.sizeSingleChunkInByte = crlstatus.sizeSingleChunkInByte
            preferences.lastChunk
            //preferences.version = crlstatus.version
            preferences.numDiAdd = crlstatus.numDiAdd
            preferences.numDiDelete = crlstatus.numDiDelete

            while(preferences.lastDownloadedChunk < crlstatus.lastChunk) {
                getRevokeList(crlstatus.version,preferences.lastDownloadedChunk + 1 )
            }
            if (preferences.lastDownloadedChunk == crlstatus.lastChunk)
            {
                //update current version
                preferences.lastDownloadedVersion== crlstatus.version
            }

        }
    }

    private suspend fun getRevokeList(version: Long, chunk : Long = 1) {
        try{
            val response = apiService.getRevokeList(version, chunk) //destinationVersion, add chunk from prefs
            val body = response.body() ?: run {
            }
            var certificateRevocationList: CertificateRevocationList = Gson().fromJson(response.body()?.string(), CertificateRevocationList::class.java)
            //Log.i("CRL", certificateRevocationList.toString())
            processRevokeList(certificateRevocationList)
            preferences.lastDownloadedChunk = preferences.lastDownloadedChunk +1
        }
        catch (e: Exception)
        {
            Log.i("exception", e.localizedMessage.toString())
        }

    }

    private suspend fun processRevokeList(certificateRevocationList: CertificateRevocationList) {
        try{

            val revokedUcviList = certificateRevocationList.revokedUcvi
            if (revokedUcviList !=null)
            {
                //todo process mRevokedUCVI adding them to realm (consider batch insert)
                Log.i("processRevokeList", " adding UCVI")

                for (revokedUcvi in revokedUcviList)
                {
                    //todo add to realm OR just do a batch insert in realm
                    Log.i("insert single ucvi", revokedUcvi.toString())
                }
            }
            else if (certificateRevocationList.delta!= null)
            {
                //Todo Delta check and processing
                Log.i("Delta", "delta")

                val deltaInsertList = certificateRevocationList.delta.insertions
                val deltaDeleteList = certificateRevocationList.delta.deletions

                if (deltaInsertList !=null)
                {
                    //Todo batch insert from Realm
                    Log.i("Delta", "delta")
                }
                if(deltaDeleteList != null)
                {
                    //todo batch delete from Realm
                    Log.i("Delta", "delta")
                }

            }
        }
        catch (e: Exception)
        {
            Log.i("crl processing exception", e.localizedMessage.toString())
        }

    }

    private suspend fun clearDB_clearPrefs() {
        try {
        //todo implement clearDB and clearPrefs (clearPrefs is already implemented, just do REALM)
        }
        catch (e : Exception)
        {
            //handle exception
        }
    }
    companion object {

        const val HEADER_KID = "x-kid"
        const val HEADER_RESUME_TOKEN = "x-resume-token"
    }

}

