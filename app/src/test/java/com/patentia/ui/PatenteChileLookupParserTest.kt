package com.patentia.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PatenteChileLookupParserTest {

    @Test
    fun `parsePatenteChileLookupPayload extracts values from next-line labels`() {
        val lookup = parsePatenteChileLookupPayload(
            """
            {
              "plateNumber":"CXDL88",
              "rawText":"Resultado de busqueda\nPropietario\nJUAN PEREZ SOTO\nRut\n12.345.678-5\nMarca\nTOYOTA\nModelo\nYARIS SPORT\nAño\n2021\nColor\nBLANCO"
            }
            """.trimIndent()
        )

        assertNotNull(lookup)
        assertEquals("CXDL88", lookup?.plateNumber)
        assertEquals("JUAN PEREZ SOTO", lookup?.ownerName)
        assertEquals("12.345.678-5", lookup?.ownerRut)
        assertEquals("TOYOTA", lookup?.vehicleMake)
        assertEquals("YARIS SPORT", lookup?.vehicleModel)
        assertEquals("2021", lookup?.vehicleYear)
        assertEquals("BLANCO", lookup?.vehicleColor)
    }

    @Test
    fun `parsePatenteChileLookupPayload extracts values from labeled pairs and scripts`() {
        val lookup = parsePatenteChileLookupPayload(
            """
            {
              "plateNumber":"BBCC11",
              "labeledPairs":[["Nombre del titular","MARIA GONZALEZ"],["Color","GRIS PLATA"]],
              "scriptPayloads":["{\"marca\":\"KIA\",\"modelo\":\"MORNING\",\"rut\":\"9.876.543-2\",\"ano\":\"2018\"}"]
            }
            """.trimIndent()
        )

        assertNotNull(lookup)
        assertEquals("MARIA GONZALEZ", lookup?.ownerName)
        assertEquals("9.876.543-2", lookup?.ownerRut)
        assertEquals("KIA", lookup?.vehicleMake)
        assertEquals("MORNING", lookup?.vehicleModel)
        assertEquals("2018", lookup?.vehicleYear)
        assertEquals("GRIS PLATA", lookup?.vehicleColor)
    }

    @Test
    fun `parsePatenteChileLookupPayload preserves direct values from alternate providers`() {
        val lookup = parsePatenteChileLookupPayload(
            """
            {
              "plateNumber":"CXDL88",
              "ownerName":"RODRIGUEZ CORTES JAIME SEBASTIAN",
              "ownerRut":"17.016.877-1",
              "vehicleMake":"TOYOTA",
              "vehicleModel":"YARIS SPORT 1.3",
              "vehicleYear":"2011"
            }
            """.trimIndent()
        )

        assertNotNull(lookup)
        assertEquals("CXDL88", lookup?.plateNumber)
        assertEquals("RODRIGUEZ CORTES JAIME SEBASTIAN", lookup?.ownerName)
        assertEquals("17.016.877-1", lookup?.ownerRut)
        assertEquals("TOYOTA", lookup?.vehicleMake)
        assertEquals("YARIS SPORT 1.3", lookup?.vehicleModel)
        assertEquals("2011", lookup?.vehicleYear)
        assertNull(lookup?.vehicleColor)
    }

    @Test
    fun `parsePatenteChileLookupPayload ignores css style noise when resolving vehicle color`() {
        val lookup = parsePatenteChileLookupPayload(
            """
            {
              "plateNumber":"CZWJ15",
              "ownerName":"NUNEZ BARRAZA KATHERINE LORENA",
              "ownerRut":"14.552.733-3",
              "vehicleMake":"CHEVROLET",
              "vehicleModel":"AVEO LII HB 1.4",
              "vehicleYear":"2011",
              "rawHtml":"<div style=\"color: transparent; display: inline-block; overflow: visible;\">ad</div>"
            }
            """.trimIndent()
        )

        assertNotNull(lookup)
        assertNull(lookup?.vehicleColor)
    }

    @Test
    fun `parsePatenteChileLookupPayload returns null when provider reports no result`() {
        val lookup = parsePatenteChileLookupPayload(
            """
            {
              "plateNumber":"ZZZZ99",
              "noResult":true,
              "rawText":"Mostrando resultados para: ZZZZ99\nPatente Tipo Marca Modelo RUT Nro. Motor Año Nombre a Rutificador"
            }
            """.trimIndent()
        )

        assertNull(lookup)
    }

    @Test
    fun `isPatenteChileLookupNoResult detects provider no result from raw webview payload`() {
        val rawResult = "\"{\\\"plateNumber\\\":\\\"ZZZZ99\\\",\\\"noResult\\\":true}\""

        val isNoResult = isPatenteChileLookupNoResult(rawResult)

        assertEquals(true, isNoResult)
    }

    @Test
    fun `parsePatenteChileLookupPayload returns null when no meaningful fields exist`() {
        val lookup = parsePatenteChileLookupPayload("""{"plateNumber":"ABCD12","rawText":"Busqueda sin coincidencias"}""")

        assertNull(lookup)
    }
}