package ar.edu.austral.inf.sd.server.model

import java.util.Objects
import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import jakarta.validation.Valid
import java.util.UUID

/**
 * 
 * @param host 
 * @param port 
 * @param name 
 */
data class RegisterRequest(

    @get:JsonProperty("host", required = true) val host: kotlin.String,

    @get:JsonProperty("port", required = true) val port: kotlin.Int,

    @get:JsonProperty("name", required = true) val name: kotlin.String,

    @get:JsonProperty("uuid", required = true) val uuid : UUID

    ) {

}

