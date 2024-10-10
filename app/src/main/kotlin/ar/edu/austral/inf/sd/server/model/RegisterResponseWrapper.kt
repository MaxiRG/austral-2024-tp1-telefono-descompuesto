package ar.edu.austral.inf.sd.server.model

import com.fasterxml.jackson.annotation.JsonProperty

data class RegisterResponseWrapper(
    @get:JsonProperty("registerResponse", required = true) val registerResponse: RegisterResponse,
    @get:JsonProperty("httpResponse", required = true) val httpResponse: HttpResponse
)