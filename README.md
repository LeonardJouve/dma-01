# DMA Labo01
*Loris Marzullo, Zaid Schouwey, Léonard Jouve*

## Protocoles applicatifs mobiles

### Introduction
Durant ce laboratoire, nous avons pu découvrir et comparer différents protocoles applicatifs mobiles:
- REST avec plusieurs manières de sérialiser et compresser les données (JSON, XML, Protobuf)
- GraphQL
- Un service de push de messages avec Firebase Cloud Messaging

### Manipulations

#### 1. Echange d’objets avec le serveur

Pour l'envoie de données au format JSON, nous avons simplement construit un tableau JSON à partir des mesures qui est ensuite transmit au serveur avec le header `Content-Type: "application/json"`.

```kotlin
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
```

L'envoie de données au format XML est fait à l'aide de la librairie `jdom2`. Un dtd permettant de spécifier le format des données transmises est également transmit.

Finalement l'envoie des données au format Protobuf est fait à l'aide du code généré par l'utilitaire protobuf à partir du fichier `.proto`.

Afin de compresser les données si souhaité, nous wrappons simplement le stream d'entrées avec un `DeflaterOutputStream`

```kotlin
val outputStream = if (compression == Compression.DEFLATE) {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    DeflaterOutputStream(connection.outputStream, deflater)
} else {
    connection.outputStream
}
```

Idem pour le stream permettant de lire la réponse.

```kotlin
val inputStream = if (connection.getHeaderField("X-Content-Encoding") == Compression.DEFLATE.name) {
    InflaterInputStream(connection.getInputStream(), Inflater(true))
} else {
    connection.getInputStream()
}
```

De la même manière que pour la sérialization, la déserialization est faites à l'aide de l'API JSON, jdom2 ainsi que le code généré par Protobuf.


Comparatif avec une vitesse réseau équivalent 5G

| Format   | Taille payload (bytes) | Taille reçue (bytes) | Gain en volume (bytes) | Temps sans compression (ms) | Temps avec compression (ms) | Gain de temps (ms) |
|----------|------------------------|----------------------|------------------------|-----------------------------|-----------------------------|--------------------|
| XML      |  602                   | 283                  | 319                    | 49                          | 32                          | 17                 |
| JSON     | 271                    | 157                  | 114                    | 42                          | 39                          | 3                  |
| Protobuf | 93                     | 92                   | 1                      | 32                          | 29                          | 3                  |

Comparatif avec une vitesse réseau équivalent 2G

| Format   | Taille payload (bytes) | Taille reçue (bytes) | Gain en volume (bytes) | Temps sans compression (ms) | Temps avec compression (ms) | Gain de temps (ms) |
|----------|------------------------|----------------------|------------------------|-----------------------------|-----------------------------|--------------------|
| XML      | 602                    | 283                  | 319                    | 120                         | 99                          | 21                 |
| JSON     | 271                    | 157                  | 114                    | 60                          | 45                          | 15                 |
| Protobuf | 93                     | 92                   | 1                      | 40                          | 35                          | 5                  |

Pourcentage de gain par format:
- XML 53%
- JSON 42%
- Protobuf 1%

On constate alors que de manière générale, le format XML est le plus lourd. Ce n'est pas étonnant car il inclus un grande quantité de texte nécéssaire à la structure XML qui ne fait pas partie des données pures.
Vient ensuite le format JSON qui est tout de même moins lourd que XML car moins verbeux.
Finalement le format Protobuf est le plus léger des 3. En effet, il s'agit d'un format binaire qui inclut uniquement les valeurs des différents attributs.

On peut également noter que le format XML et JSON on un bien plus gros bénéfice à compresser leurs données que le format Protobuf.

En 5G, la transmission des données est rapide alors le gains de temps sur la compression des données est faible car le temps de calcul nécéssaire à la compression / décompression annule ce gain.

En 2G, le gain est plus important car la transmission des données est lente. Diminuer la quantité de données à transmettre malgré un coup de calcul plus important est intéressant.

#### 2. Requêtes au format GraphQL

Par soucis de factorisation, nous avons créé une fonction supplémentaire `postQuery`. Celle-ci se charge d'établir la connection avec le serveur, d'envoyer une query passée en paramètre et de retourner la réponse reçue. Cela facilite l'ajout de requêtes, puisqu'il suffit d'ajouter une fonction supplémentaire faisant un appel à `postQuery`, avec la query en question et la logique de parsing correspondante.

Pour ce qui est d'une utilisation mobile de cette partie de l'application, certains aspects pourraient être améliorés. Tout d'abord, le fragment ne sauvegarde pas l'auteur et les livres actuellement affichés. Cela signifie que si le fragment est recréée (par exemple, en changeant d'onglet ou l'orientation du téléphone), l'affichage se réinitialisera sur le premier auteur de la liste (ici, J.K. Rowling). De plus, la liste des auteurs entière est demandée au serveur à chacune de ces réinitialisations. Sachant que la DB comporte près de 9'200 auteurs, non seulement cela crée des réponses lourdes de la part du serveur, mais une telle quantité de données est inutile à afficher en tout temps (le menu déroulant est d'ailleurs très peu pratique, puisque tous les auteurs sont affichés en une fois). Pour optimiser cela, l'application pourrait avoir une barre de recherche, qui récupère un sous-ensemble d'auteurs correspondant au texte saisi. Une autre manière de faire serait de récupérer la liste des auteurs une seule fois et de la garder en cache pour éviter les appels répétés.

#### 3. Push de message (Firebase Cloud Messaging)

Nous avons ajouté FCM comme indiqué dans le document fournis.

Notre application affiche le dernier message reçu et permet également de recevoir une command `clear` permettant de supprimer les anciens messages stockés. (`command="clear"`)

FCM attribut à chaque instance de l'application un token permettant de l'identifier afin d'envoyer des messages au bon appareils.
Il est important de garder à jour ces token à chaque fois qu'un nouveau est généré.
On peut voir celà à l'aide de la méthode `onNewToken` du service Firebase de notre application.
Un premier token est généré au premier lancement de l'application.
La documentation FCM indique 4 cas dans lesquels un nouveau token pourrait être attribué.
- L'application est restaurée sur un nouvel appareil
- L'utilisateur désinstalle ou réinstalle l'application
- L'utilisateur efface les données de l'application
- L'application redevient active une fois que FCM a expiré son jeton existant.

Ce token permet d'identifier auprès de FCM les différents appareils associés à l'utilisateur.
Pour une application mobile comme *WhatsApp*, les tokens assignés par FCM doivent être transmis au backend de *Whatsapp* qui va maintenir une table d'association (utilisateur -> tokens).
Ainsi, quand un utilisateur envoie un message, le serveur peut récupérer les tokens associés au destinataire du message.
Il peut ensuite envoyer une requête à FCM afin d'envoyer un message push au appareils associés aux tokens.

La méthode `onNewToken` permet alors de détecter un changement de token afin de permettre à l’app de mettre à jour le backend.
