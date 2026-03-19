package ch.heigvd.iict.dma.labo1.repositories

import android.util.JsonReader
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import ch.heigvd.iict.dma.labo1.models.*
import ch.heigvd.iict.dma.protobuf.MeasuresOuterClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.URL
import java.util.zip.Deflater
import java.util.zip.DeflaterInputStream
import java.util.zip.DeflaterOutputStream
import kotlin.system.measureTimeMillis
import ch.heigvd.iict.dma.protobuf.measure as protobufMeasure
import ch.heigvd.iict.dma.protobuf.measures as protobufMeasures
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class MeasuresRepository(private val scope : CoroutineScope,
                         private val dtd : String = "https://mobile.iict.ch/measures.dtd",
                         private val httpUrl : String = "http://mobile.iict.ch/api",
                         private val httpsUrl : String = "https://mobile.iict.ch/api") {

    private val _measures = MutableLiveData(mutableListOf<Measure>())
    val measures = _measures.map { mList -> mList.toList().map { el -> el.copy() } }

    private val _requestDuration = MutableLiveData(-1L)
    val requestDuration : LiveData<Long> get() = _requestDuration

    fun generateRandomMeasures(nbr: Int = 3) {
        addMeasures(Measure.getRandomMeasures(nbr))
    }

    fun resetRequestDuration() {
        _requestDuration.postValue(-1L)
    }

    fun addMeasure(measure: Measure) {
        addMeasures(listOf(measure))
    }

    fun addMeasures(measures: List<Measure>) {
        val l = _measures.value!!
        l.addAll(measures)
        _measures.postValue(l)
    }

    fun clearAllMeasures() {
        _measures.postValue(mutableListOf())
    }

    fun sendMeasureToServer(encryption : Encryption, compression : Compression, networkType : NetworkType, serialisation : Serialisation) {
        scope.launch(Dispatchers.Default) {
            val url = when (encryption) {
                Encryption.DISABLED -> httpUrl
                Encryption.SSL -> httpsUrl
            }

            val elapsed = measureTimeMillis {
                val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", "gr_2")
                connection.setRequestProperty("X-Content-Encoding", compression.name)
                if (networkType != NetworkType.RANDOM) {
                    connection.setRequestProperty("X-Network", networkType.name)
                }

                val serializedMeasures: ByteArray = when (serialisation) {
                    Serialisation.PROTOBUF -> {
                        connection.setRequestProperty("Content-Type", "application/protobuf")

                        protobufMeasures {
                            _measures.value?.forEach { m ->
                                measures += protobufMeasure {
                                    id = m.id
                                    type = m.type.name
                                    value = m.value
                                    date = m.date.timeInMillis
                                }
                            }
                        }.toByteArray()
                    }
                    Serialisation.JSON -> {
                        connection.setRequestProperty("Content-Type", "application/json")

                        val jsonArray = JSONArray()
                        _measures.value?.forEach { m ->
                            val jsonObject = JSONObject()
                            jsonObject.put("id", m.id)
                            jsonObject.put("status", m.status)
                            jsonObject.put("type", m.type.name)
                            jsonObject.put("value", m.value)
                            jsonObject.put("date", m.date.timeInMillis)
                            jsonArray.put(jsonObject)
                        }

                        jsonArray.toString().toByteArray()
                    }
                    Serialisation.XML -> {
                        connection.setRequestProperty("Content-Type", "application/xml")
                        // TODO
                        throw NotImplementedError()
                    }
                }

                val outputStream = if (compression == Compression.DEFLATE) {
                    DeflaterOutputStream(connection.outputStream, deflater)
                } else {
                    connection.outputStream
                }

                outputStream.use {
                    it.write(serializedMeasures)
                }

                val responseCode = connection.responseCode
                if (responseCode != HTTP_OK) {
                    val error = connection.getHeaderField("X-Error")
                    throw RuntimeException("invalid response code: $responseCode / error: $error")
                }

                val inputStream = if (connection.getHeaderField("X-Content-Encoding") == Compression.DEFLATE.name) {
                    InflaterInputStream(connection.getInputStream(), Inflater(true))
                } else {
                    connection.getInputStream()
                }

                inputStream.use { stream ->
                    when(serialisation){
                        Serialisation.PROTOBUF -> {
                            val measuresAck = MeasuresOuterClass.MeasuresAck.newBuilder().mergeFrom(stream).build()
                            measuresAck.measuresList.forEach { ackMeasure ->
                                _measures.value?.find { m -> m.id == ackMeasure.id }?.let {
                                    it.status = when (ackMeasure.status) {
                                        MeasuresOuterClass.Status.OK -> Measure.Status.OK
                                        MeasuresOuterClass.Status.NEW -> Measure.Status.NEW
                                        else -> Measure.Status.ERROR
                                    }
                                }
                            }
                        }

                        Serialisation.JSON -> {
                            val bufferedReader = stream.bufferedReader()
                            val responseString = bufferedReader.readText()
                            val jsonArray = org.json.JSONArray(responseString)
                            for (i in 0 until jsonArray.length()) {
                                val jsonObject = jsonArray.getJSONObject(i)
                                val id = jsonObject.getInt("id")
                                val statusString = jsonObject.getString("status")
                                _measures.value?.find { m -> m.id == id }?.let { measure ->
                                    measure.status = when (statusString) {
                                        "OK" -> Measure.Status.OK
                                        "NEW" -> Measure.Status.NEW
                                        else -> Measure.Status.ERROR
                                    }
                                }
                            }
                        }

                        Serialisation.XML -> {
                            //TODO
                        }
                    }

                }
            }

            _requestDuration.postValue(elapsed)
        }
    }

}