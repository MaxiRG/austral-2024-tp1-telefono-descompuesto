package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.PlayApiService
import ar.edu.austral.inf.sd.server.api.RegisterNodeApiService
import ar.edu.austral.inf.sd.server.api.RelayApiService
import ar.edu.austral.inf.sd.server.api.BadRequestException
import ar.edu.austral.inf.sd.server.api.ReconfigureApiService
import ar.edu.austral.inf.sd.server.api.UnregisterNodeApiService
import ar.edu.austral.inf.sd.server.model.HttpResponse
import ar.edu.austral.inf.sd.server.model.PlayResponse
import ar.edu.austral.inf.sd.server.model.RegisterResponse
import ar.edu.austral.inf.sd.server.model.RegisterResponseWrapper
import ar.edu.austral.inf.sd.server.model.Signature
import ar.edu.austral.inf.sd.server.model.Signatures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class ApiServicesImpl : RegisterNodeApiService, RelayApiService, PlayApiService, UnregisterNodeApiService,
    ReconfigureApiService {

    @Value("\${server.name:nada}")
    private val myServerName: String = ""

    @Value("\${server.port:8080}")
    private val myServerPort: Int = 0
    private val nodes: MutableList<RegisterResponse> = mutableListOf()
    private var nextNode: RegisterResponse? = null
    private val messageDigest = MessageDigest.getInstance("SHA-512")
    private val salt = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    private val currentRequest
        get() = (RequestContextHolder.getRequestAttributes() as ServletRequestAttributes).request
    private var resultReady = CountDownLatch(1)
    private var currentMessageWaiting = MutableStateFlow<PlayResponse?>(null)
    private var currentMessageResponse = MutableStateFlow<PlayResponse?>(null)
    private var xGameTimestamp: Int = 0
    private var timeoutInSeconds : Long = 5
    private val myUuid = UUID.randomUUID()
    private val scope = CoroutineScope(Dispatchers.Default)
    private var coordinatorHost = ""
    private var coordinatorPort = -1
    private var messageArrived = true
    private var signaturesArrived = true

    override fun registerNode(host: String?, port: Int?, uuid: UUID?, salt: String?, name: String?): RegisterResponseWrapper {

        // Si hay un dato vacio, devolver 400
        if (host == null || port == null || uuid == null || salt == null || name == null) {
            throw BadRequestException("Invalid request")
        }
        // Si el UUID ya existe, pero la clave privada es distinta, devolver 401 (Unauthorized)
        if (nodes.any { it.uuid == uuid && it.salt != salt }) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "UUID already exists but the salt does not match")
        }

        // Si el UUID ya existe y la clave privada es la misma, devolver 202
        if (nodes.any { it.uuid == uuid && it.salt == salt }) {
            val existingNode = nodes.find { it.uuid == uuid }
            val registerResponse =  RegisterResponse(existingNode!!.nextHost, existingNode.nextPort, existingNode.uuid, existingNode.salt, timeoutInSeconds, existingNode.xGameTimestamp)
            val httpResponse = HttpResponse(202, "UUID already exists and salt matches.")
            return RegisterResponseWrapper(registerResponse, httpResponse)
        }


        val nextNode = if (nodes.isEmpty()) {
            // es el primer nodo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, myUuid, this.salt, timeoutInSeconds, xGameTimestamp)
            nodes.add(me)
            me
        } else {
            nodes.last()
        }
        val node = RegisterResponse(currentRequest.serverName, port, uuid, salt, timeoutInSeconds, xGameTimestamp)
        nodes.add(node)

        val registerResponse = RegisterResponse(nextNode.nextHost, nextNode.nextPort, nextNode.uuid, nextNode.salt, timeoutInSeconds, xGameTimestamp)
        return RegisterResponseWrapper(registerResponse, HttpResponse(200, "success"))
    }

    override fun relayMessage(message: String, signatures: Signatures, xGameTimestamp: Int?): Signature {
        val receivedHash: String = doHash(message.encodeToByteArray(), salt)
        val receivedContentType = currentRequest.getPart("message")?.contentType ?: "nada"
        val receivedLength = message.length
        if (nextNode != null) {
            if(xGameTimestamp == null ||xGameTimestamp < this.xGameTimestamp) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Wrong X-Game-Timestamp.")
            } else {
                this.xGameTimestamp = xGameTimestamp
            }
            val success : Boolean = retrySendRelay(message, receivedContentType, signatures, 1)
            if(!success) {
                println("Relay failed, returning message to coordinator.")
                println(coordinatorHost)
                println(coordinatorPort)
                sendRelayMessage(message, receivedContentType, coordinatorHost, coordinatorPort, signatures)
            }
        } else {
            // me llego algo, no lo tengo que pasar.
            if (currentMessageWaiting.value == null) throw BadRequestException("no waiting message")
            val current = currentMessageWaiting.getAndUpdate { null }!!
            messageArrived = checkMessageArrived(receivedHash, current)
            signaturesArrived = checkSignaturesArrived(signatures, message)
            val response: PlayResponse = current.copy(
                contentResult = "Success",
                receivedHash = receivedHash,
                receivedLength = receivedLength,
                receivedContentType = receivedContentType,
                signatures = signatures
            )
            currentMessageResponse.update { response }
            resultReady.countDown()
        }
        return Signature(
            name = myServerName,
            hash = receivedHash,
            contentType = receivedContentType,
            contentLength = receivedLength
        )
    }


    private fun retrySendRelay(
        message: String,
        receivedContentType: String,
        signatures: Signatures,
        times : Int
    ):Boolean {
        var counter = times
        while (counter > 0) {
            try {
                if(nextNode != null){
                    val response = sendRelayMessage(message, receivedContentType, nextNode!!.nextHost, nextNode!!.nextPort, signatures)
                    if (response != null) {
                        if (response.statusCode == HttpStatus.OK) {

                            return true
                        } else {
                            counter--
                        }
                    } else {
                        return false
                    }
                } else {
                    return false
                }
            } catch (e: Exception) {
                counter--
            }
        }
        return false
    }

    private fun checkMessageArrived(receivedHash: String, current: PlayResponse) : Boolean {
        if (receivedHash != current.originalHash ) {
            return false
        } else {
            return true
        }
    }

    private fun checkSignaturesArrived(
        signatures: Signatures,
        message : String
    ) : Boolean {
        val expectedSignatures: List<String> = nodes.map { playerSign(message, it) }
        val orderedExpectedSignatures: List<String> = listOf(expectedSignatures.first()) + expectedSignatures.drop(1).reversed()
        val actualSignatures = signatures.items.map { it.hash }
        val allSignaturesArrivedCorrectly = orderedExpectedSignatures == actualSignatures
        if (!allSignaturesArrivedCorrectly) {
            return false
        } else { return true}
    }

    override fun sendMessage(body: String): PlayResponse {
        if(timeoutInSeconds == 0.toLong()) timeoutInSeconds = 5
        if (nodes.isEmpty()) {
            // inicializamos el primer nodo como yo mismo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, myUuid, salt, timeoutInSeconds, xGameTimestamp)
            nodes.add(me)
        }
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType
        sendRelayMessage(body, contentType, nodes.last().nextHost, nodes.last().nextPort, Signatures(listOf()))
        if(!resultReady.await(timeoutInSeconds, TimeUnit.SECONDS)) { //Aca definimos cuanto tarda en hacer time-out el play.
            resultReady = CountDownLatch(1)
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Gateway Timeout")
        }
        resultReady = CountDownLatch(1)
        xGameTimestamp++
        if(!messageArrived) {
            messageArrived = true
            signaturesArrived = true
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Message did not arrive correctly")
        }
        if(!signaturesArrived) {
            signaturesArrived = true
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Signatures did not arrive correctly.")
        }
        return currentMessageResponse.value!!
    }

    override suspend fun unregisterNode(uuid: UUID?, salt: String?): String {
        val successMessage = "Unregister successful!"
        val nodeToUnregister: RegisterResponse? = nodes.find { it.uuid == uuid && it.salt == salt }
        if(nodeToUnregister == null) {
            throw BadRequestException("uuid or salt incorrect")
        }

        val indexOfUnregister: Int = nodes.indexOf(nodeToUnregister)
        val nodeToReconfigure = nodes.getOrNull(indexOfUnregister+1)

        if(nodeToReconfigure == null) {
            nodes.removeLast()
            return successMessage
        } else {
            nodes.removeAt(indexOfUnregister)

            val newNextNode = nodes.getOrNull(indexOfUnregister - 1) ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "No next node to reconfigure to")

            val client = WebClient.builder()
                .baseUrl("http://${nodeToReconfigure.nextHost}:${nodeToReconfigure.nextPort}")
                .defaultHeader("Content-Type", "application/json")
                .build()

            val reconfigureNodeResponse : String = client.post()
                .uri { builder ->
                    builder.path("/reconfigure")
                        .queryParam("uuid", nodeToReconfigure.uuid)
                        .queryParam("salt", nodeToReconfigure.salt)
                        .queryParam("nextHost", newNextNode.nextHost)
                        .queryParam("nextPort", newNextNode.nextPort)
                        .queryParam("X-Game-Timestamp", xGameTimestamp)
                        .build()
                }
                .retrieve()
                .awaitBody()

            return successMessage

        }
    }

    override fun reconfigure(
        uuid: UUID?,
        salt: String?,
        nextHost: String?,
        nextPort: Int?,
        xGameTimestamp: Int?
    ): String {
        if(uuid == myUuid && salt == this.salt) {
            scope.launch {
                delay(5000) // Wait 5 seconds
                applyNewSettings(nextHost, nextPort, xGameTimestamp)
            }
            return "Configuration update scheduled"
        } else {
            throw BadRequestException("uuid or salt incorrect")
        }
    }

    private fun applyNewSettings(nextHost: String?, nextPort: Int?, xGameTimestamp: Int?) {
       if( nextHost != null && nextPort != null && nextNode != null) {
           val newNextNode = RegisterResponse(nextHost, nextPort, nextNode!!.uuid, nextNode!!.salt, nextNode!!.timeout, nextNode!!.xGameTimestamp)
           nextNode = newNextNode
       }
    }

    internal suspend fun registerToServer(registerHost: String, registerPort: Int) {
        val client = WebClient.builder()
            .baseUrl("http://${registerHost}:${registerPort}")  // Replace with your API base URL
            .defaultHeader("Content-Type", "application/json")
            .build()

        val registerNodeResponse: RegisterResponse = client.post()
            .uri { builder ->
                builder.path("/register-node")
                    .queryParam("host", myServerName)
                    .queryParam("port", myServerPort)
                    .queryParam("uuid", myUuid)
                    .queryParam("salt", salt)
                    .queryParam("name", myServerName)
                    .build()
            }
            .retrieve()
            .awaitBody()


        println("nextNode = ${registerNodeResponse}")
        nextNode = with(registerNodeResponse) { RegisterResponse(nextHost, nextPort, uuid, salt, timeoutInSeconds, xGameTimestamp) }
        timeoutInSeconds = nextNode!!.timeout
        xGameTimestamp = nextNode!!.xGameTimestamp
        coordinatorHost = registerHost
        coordinatorPort = registerPort

    }

    private fun sendRelayMessage(
        body: String,
        contentType: String,
        host : String,
        port : Int,
        signatures: Signatures
    ): ResponseEntity<Void>? {
        val url = "http://${host}:${port}"
        try {
            val newSignatures = Signatures(signatures.items + clientSign(body, contentType))

            val multipartData: MultiValueMap<String, Any> = LinkedMultiValueMap()
            multipartData.add("message", body)
            multipartData.add("signatures", newSignatures)

            return WebClient.builder().baseUrl(url)
                .build()
                .post()
                .uri("/relay")
                .header("X-Game-Timestamp", xGameTimestamp.toString())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(
                    BodyInserters.fromMultipartData(
                    multipartData,
                ))
                .retrieve()
                .toBodilessEntity()
                .block()
        } catch (e: Exception) {
//             Handle any exceptions as needed
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong sending relay message.")
        }
    }




    private fun clientSign(message: String, contentType: String): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        return Signature(myServerName, receivedHash, contentType, message.length)
    }

    private fun playerSign(message: String, node: RegisterResponse): String {
        val saltBytes = Base64.getDecoder().decode(node.salt)

        // Update the message digest with the salt
        messageDigest.update(saltBytes)


        // Compute the hash of the body
        val computedHash = messageDigest.digest(message.encodeToByteArray())

        // Encode the computed hash to Base64
        return Base64.getEncoder().encodeToString(computedHash)
    }

    private fun newResponse(body: String) = PlayResponse(
        "Unknown",
        currentRequest.contentType,
        body.length,
        doHash(body.encodeToByteArray(), salt),
        "Unknown",
        -1,
        "N/A",
        Signatures(listOf())
    )

    private fun doHash(body: ByteArray, salt: String): String {
        val saltBytes = Base64.getDecoder().decode(salt)
        messageDigest.update(saltBytes)
        val digest = messageDigest.digest(body)
        return Base64.getEncoder().encodeToString(digest)
    }

    companion object {
        fun newSalt(): String = Base64.getEncoder().encodeToString(Random.nextBytes(9))
    }
}