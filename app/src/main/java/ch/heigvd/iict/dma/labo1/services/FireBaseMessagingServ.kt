package ch.heigvd.iict.dma.labo1.services

import android.util.Log
import ch.heigvd.iict.dma.labo1.Labo1Application
import ch.heigvd.iict.dma.labo1.models.Message
import ch.heigvd.iict.dma.labo1.repositories.MessagesRepository
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Calendar

class FireBaseMessagingServ : FirebaseMessagingService() {
    private lateinit var messageRepository: MessagesRepository

    override fun onCreate() {
        super.onCreate()
        messageRepository = MessagesRepository((application as Labo1Application).messagesDao)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FireBaseMessagingServ", "Nouveau token : $token")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d("FireBaseMessagingServ", "Message reçu de : ${message.from} ${message.data}")

        if (message.from == "/topics/monTopic") {
            messageRepository.insert(
                Message(
                    sentDate = Calendar.getInstance().apply {
                        timeInMillis = message.sentTime
                    },
                    receptionDate = Calendar.getInstance(),
                    message = message.data.toString(),
                    command = message.data["command"]
                ))
            Log.d("FireBaseMessagingServ", "Insert message")
            when (message.data["command"]) {
                "clear" -> {
                    Log.d("FireBaseMessagingServ", "clear messages")
                    messageRepository.deleteAllMessage()
                }
            }
        }
    }
}