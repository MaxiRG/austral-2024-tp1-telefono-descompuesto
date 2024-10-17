package ar.edu.austral.inf.sd.server.model

import com.fasterxml.jackson.annotation.JsonProperty

data class HttpResponse(
    @get:JsonProperty("httpCode", required = true) val httpCode: Int,
    @get:JsonProperty("message", required = true) val message: String
)