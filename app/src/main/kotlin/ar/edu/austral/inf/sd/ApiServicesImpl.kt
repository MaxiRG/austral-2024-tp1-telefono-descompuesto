package ar.edu.austral.inf.sd

import ar.edu.austral.inf.sd.server.api.PlayApiService
import ar.edu.austral.inf.sd.server.api.RegisterNodeApiService
import ar.edu.austral.inf.sd.server.api.RelayApiService
import ar.edu.austral.inf.sd.server.api.BadRequestException
import ar.edu.austral.inf.sd.server.api.ReconfigureApiService
import ar.edu.austral.inf.sd.server.api.UnregisterNodeApiService
import ar.edu.austral.inf.sd.server.model.HttpResponse
import ar.edu.austral.inf.sd.server.model.PlayResponse
import ar.edu.austral.inf.sd.server.model.RegisterRequest
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
import org.springframework.web.client.HttpServerErrorException.InternalServerError
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
    private var timeoutInSeconds : Long = 0
    private val myUuid = UUID.randomUUID()
    private val scope = CoroutineScope(Dispatchers.Default)

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
            val registerResponse: RegisterResponse =  RegisterResponse(existingNode!!.nextHost, existingNode.nextPort, existingNode.uuid, existingNode.salt, timeoutInSeconds, existingNode.xGameTimestamp)
            val httpResponse = HttpResponse(202, "UUID already exists and salt matches.")
            return RegisterResponseWrapper(registerResponse, httpResponse)
        }


        val nextNode = if (nodes.isEmpty()) {
            // es el primer nodo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, uuid, salt, timeoutInSeconds, xGameTimestamp)
            nodes.add(me)
            me
        } else {
            nodes.last()
        }
        val node = RegisterResponse(currentRequest.serverName, myServerPort, uuid, salt, timeoutInSeconds, xGameTimestamp)
        nodes.add(node)

        val registerResponse: RegisterResponse = RegisterResponse(nextNode.nextHost, nextNode.nextPort, nextNode.uuid, nextNode.salt, timeoutInSeconds, xGameTimestamp)
        return RegisterResponseWrapper(registerResponse, HttpResponse(200, "success"))
    }

    override fun relayMessage(message: String, signatures: Signatures, xGameTimestamp: Int?): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        val receivedContentType = currentRequest.getPart("message")?.contentType ?: "nada"
        val receivedLength = message.length
        if (nextNode != null) {
            if(xGameTimestamp != this.xGameTimestamp) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Wrong X-Game-Timestamp.")
            }
            try{
                val response = sendRelayMessage(message, receivedContentType, nextNode!!, signatures)
                if (response != null) {
                    if(response.statusCode != HttpStatus.OK) {
                        throw ResponseStatusException(HttpStatus.ACCEPTED, "Accepted and sent to next, but next didnt return 200")
                    }
                }
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Something went wrong sending relay message.")
            }
        } else {
            // me llego algo, no lo tengo que pasar.
            // Aca se define la respuesta al /play. Faltan aclarar las de que pasa en cada caso.
            if (currentMessageWaiting.value == null) throw BadRequestException("no waiting message")
            val current = currentMessageWaiting.getAndUpdate { null }!!
            val response = current.copy(
                contentResult = if (receivedHash == current.originalHash) "Success" else "Failure",
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

    override fun sendMessage(body: String): PlayResponse {
        if (nodes.isEmpty()) {
            if(timeoutInSeconds == 0.toLong()) timeoutInSeconds = 5

            // inicializamos el primer nodo como yo mismo
            val me = RegisterResponse(currentRequest.serverName, myServerPort, myUuid, salt, timeoutInSeconds, xGameTimestamp)
            nodes.add(me)
        }
        currentMessageWaiting.update { newResponse(body) }
        val contentType = currentRequest.contentType
        sendRelayMessage(body, contentType, nodes.last(), Signatures(listOf()))
        if(!resultReady.await(timeoutInSeconds, TimeUnit.SECONDS)) { //Aca definimos cuanto tarda en hacer time-out el play.
            resultReady = CountDownLatch(1)
            throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Gateway Timeout")
        }
        resultReady = CountDownLatch(1)
        return currentMessageResponse.value!!
    }

    override fun unregisterNode(uuid: UUID?, salt: String?): String {
        TODO("Not yet implemented")
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
            .baseUrl("$registerHost:$registerPort")  // Replace with your API base URL
            .defaultHeader("Content-Type", "application/json")
            .build()

        val registerNodeResponse: RegisterResponse = client.post().uri("/register-node").bodyValue(RegisterRequest(myServerName, myServerPort, salt, myUuid)).retrieve().awaitBody()

        println("nextNode = ${registerNodeResponse}")
        nextNode = with(registerNodeResponse) { RegisterResponse(nextHost, nextPort, uuid, salt, timeoutInSeconds, xGameTimestamp) }
        timeoutInSeconds = nextNode!!.timeout
        xGameTimestamp = nextNode!!.xGameTimestamp

    }

    private fun sendRelayMessage(
        body: String,
        contentType: String,
        relayNode: RegisterResponse,
        signatures: Signatures
    ): ResponseEntity<Void>? {
        val url = "http://${relayNode.nextHost}:${relayNode.nextPort}"
        try {
            val newHash = doHash(body.encodeToByteArray(), salt)
            val newSignatures = signatures.items + Signature(myServerName, newHash , contentType, body.length )

            val multipartData: MultiValueMap<String, Any> = LinkedMultiValueMap()
            multipartData.add("message", BodyInserters.fromValue(body))
            multipartData.add("signatures", BodyInserters.fromValue(newSignatures))

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
            // Handle any exceptions as needed
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong sending relay message.")
        }
    }

    private fun clientSign(message: String, contentType: String): Signature {
        val receivedHash = doHash(message.encodeToByteArray(), salt)
        return Signature(myServerName, receivedHash, contentType, message.length)
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