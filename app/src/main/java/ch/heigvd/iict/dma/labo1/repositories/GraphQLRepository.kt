package ch.heigvd.iict.dma.labo1.repositories

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import ch.heigvd.iict.dma.labo1.models.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.URL
import kotlin.system.measureTimeMillis

class GraphQLRepository(private val scope : CoroutineScope, private val httpsUrl : String = "https://mobile.iict.ch/graphql") {

    private val _working = MutableLiveData(false)
    val working : LiveData<Boolean> get() = _working

    private val _authors = MutableLiveData<List<Author>>(emptyList())
    val authors : LiveData<List<Author>> get() = _authors

    private val _books = MutableLiveData<List<Book>>(emptyList())
    val books : LiveData<List<Book>> get() = _books

    private val _requestDuration = MutableLiveData(-1L)
    val requestDuration : LiveData<Long> get() = _requestDuration

    fun resetRequestDuration() {
        _requestDuration.postValue(-1L)
    }

    // Fonction utilitaire pour généraliser l'envoi de queries GraphQL
    fun postQuery(query: String) : String {
        val connection = URL(httpsUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")

        val outputStream = connection.outputStream
        outputStream.use {
            it.write(query.toByteArray())
        }

        val responseCode = connection.responseCode
        if (responseCode != HTTP_OK) {
            val error = connection.getHeaderField("X-Error")
            throw RuntimeException("invalid response code: $responseCode / error: $error")
        }

        val inputStream = connection.inputStream
        inputStream.use { stream ->
            return stream.bufferedReader().readText()
        }
    }

    fun loadAllAuthorsList() {
        scope.launch(Dispatchers.Default) {
            val elapsed = measureTimeMillis {

                val query = """
                            {
                                "query": "{findAllAuthors{id, name}}"
                            }
                            """.trimIndent()
                val response = postQuery(query)

                try {
                    val responseParsed = JsonParser.parseString(response).asJsonObject["data"].asJsonObject["findAllAuthors"].asJsonArray
                    val authorsList : List<Author> = Gson().fromJson(responseParsed, object : TypeToken<List<Author>>() {}.type)
                    _authors.postValue(authorsList)
                } catch (e : Exception) {
                    throw RuntimeException("thrown error: $e")
                }
            }
            _requestDuration.postValue(elapsed)
        }
    }

    fun loadBooksFromAuthor(author: Author) {
        scope.launch(Dispatchers.Default) {
            val elapsed = measureTimeMillis {

                val query = """
                            {
                                "query": "{findAuthorById(id: ${author.id}){books{id, title, publicationDate, authors{id, name}}}}"
                            }
                            """.trimIndent()
                val response = postQuery(query)

                try {
                    val responseParsed = JsonParser.parseString(response).asJsonObject["data"].asJsonObject["findAuthorById"].asJsonObject["books"].asJsonArray
                    val booksList : List<Book> = Gson().fromJson(responseParsed, object : TypeToken<List<Book>>() {}.type)
                    _books.postValue(booksList)
                } catch (e : Exception) {
                    throw RuntimeException("thrown error: $e")
                }
            }
            _requestDuration.postValue(elapsed)
        }
    }

    companion object {
        //placeholder data - to remove
        //private val testAuthors = listOf(Author(-1, "Test Author", emptyList()))
        //private val testBooks = listOf(Book(-1, "Test Title", "01.01.2024", testAuthors))
    }
}