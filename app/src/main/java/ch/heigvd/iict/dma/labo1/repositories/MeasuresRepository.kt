package ch.heigvd.iict.dma.labo1.repositories

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.map
import ch.heigvd.iict.dma.labo1.models.*
import ch.heigvd.iict.dma.protobuf.MeasuresOuterClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jdom2.DocType
import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.input.SAXBuilder
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.DeflaterInputStream
import java.util.zip.DeflaterOutputStream
import kotlin.system.measureTimeMillis
import ch.heigvd.iict.dma.protobuf.measure as protobufMeasure
import ch.heigvd.iict.dma.protobuf.measures as protobufMeasures

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
                        // TODO
                        throw NotImplementedError()
                    }
                    Serialisation.XML -> {
                        connection.setRequestProperty("Content-Type", "application/xml")

                        val root = Element("measures")
                        val doc = Document(root)

                        for (m in measures.value!!) {

                            val measure = Element("measure")
                                .setAttribute("id", m.id.toString())
                                .setAttribute("status", m.status.toString())

                            measure.addContent(Element("type").setText(m.type.toString()))
                            measure.addContent(Element("value").setText(m.value.toString()))

                            val sdf = SimpleDateFormat(
                                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                                Locale.getDefault()
                            )
                            sdf.timeZone = m.date.timeZone
                            measure.addContent(Element("date").setText(sdf.format(m.date.time)))

                            root.addContent(measure)
                        }

                        doc.setDocType(DocType("measures", "https://mobile.iict.ch/measures.dtd"))

                        val outputter = XMLOutputter(Format.getPrettyFormat())

                        /*
                        val writer = StringWriter()
                        outputter.output(doc, writer)
                        Log.d("XML_OUTPUT", writer.toString())
                        */

                        outputter.outputString(doc).toByteArray()
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
                    DeflaterInputStream(connection.getInputStream(), deflater)
                } else {
                    connection.getInputStream()
                }

                when (serialisation) {
                    Serialisation.PROTOBUF -> {

                        inputStream.use {
                            val measuresAck =
                                MeasuresOuterClass.MeasuresAck.newBuilder().mergeFrom(it).build()
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
                    }
                    Serialisation.JSON -> {
                        // TODO
                    }
                    Serialisation.XML -> {

                        val saxBuilder = SAXBuilder()
                        saxBuilder.setFeature("http://xml.org/sax/features/external-general-entities", false)

                        saxBuilder.build(InputStreamReader(inputStream))
                            .rootElement
                            .getChildren("measure")
                            .associate {
                                it.getAttributeValue("id").toInt() to Measure.Status.valueOf(it.getAttributeValue("status"))
                            }
                    }
                }
            }

            _requestDuration.postValue(elapsed)
        }
    }
}