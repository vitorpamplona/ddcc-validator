package org.who.gdhcnvalidator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.knowledge.KnowledgeManager
import com.google.android.fhir.testing.jsonParser
import com.google.android.fhir.workflow.FhirOperator
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.who.gdhcnvalidator.services.*
import org.who.gdhcnvalidator.services.cql.CqlBuilder
import org.who.gdhcnvalidator.test.BaseTrustRegistryTest
import java.io.File
import java.util.*
import kotlin.system.measureTimeMillis
import kotlin.time.measureTimedValue

@RunWith(AndroidJUnit4::class)
class QRViewTest: BaseTrustRegistryTest() {
    private val qrUnpacker = QRDecoder(registry)

    private val fhirContext = FhirContext.forCached(FhirVersionEnum.R4)
    private lateinit var fhirEngine: FhirEngine
    private lateinit var fhirOperator: FhirOperator
    private lateinit var knowledgeManager: KnowledgeManager

    private val ddccPass = CqlBuilder.compileAndBuild(inputStream("TestPass-1.0.0.cql")!!)

    private fun writeToFile(resource: BaseResource): File {
        val context: Context = ApplicationProvider.getApplicationContext()
        return File(context.cacheDir, resource.id).apply {
            val json = jsonParser.encodeResourceToString(resource)
            writeText(json)
        }
    }

    @Before
    fun setUp() = runBlocking {
        val elapsed = measureTimeMillis {
            val ctx: Context = ApplicationProvider.getApplicationContext()
            fhirEngine = FhirEngineProvider.getInstance(ctx)
            knowledgeManager = KnowledgeManager.create(
                context = ctx,
                inMemory = true,
                downloadedNpmDir = ctx.cacheDir
            )
            fhirOperator = FhirOperator.Builder(ctx)
                .fhirEngine(fhirEngine)
                .fhirContext(fhirContext)
                .knowledgeManager(knowledgeManager)
                .build()

            knowledgeManager.index(writeToFile(ddccPass))

            TimeZone.setDefault(TimeZone.getTimeZone("GMT"))
        }
        println("TIME: Test Initialized in $elapsed ms")
    }

    private suspend fun loadBundle(bundle: Bundle?) {
        checkNotNull(bundle)
        for (entry in bundle.entry) {
            when (entry.resource.resourceType) {
                ResourceType.Library -> knowledgeManager.index(writeToFile(entry.resource as Library))
                ResourceType.Bundle -> Unit
                else -> fhirEngine.create(entry.resource)
            }
        }
    }

    private fun patId(bundle: Bundle?): String {
        checkNotNull(bundle)
        return bundle.entry.filter { it.resource is Patient }.first().resource.id.removePrefix("Patient/")
    }


    @Test
    fun viewDVCTestQR() = runBlocking {

        val (qr1, elapsedOpen) = measureTimedValue {
            open("DVCTestQR.txt")
        }
        println("TIME: Opened QR in $elapsedOpen")

        val (verified, elapsedDecoded) = measureTimedValue {
            qrUnpacker.decode(qr1)
        }
        println("TIME: Decoded QR in $elapsedDecoded")

        loadBundle(verified.contents)

        assertNotNull(verified.contents)
        assertNotNull(patId(verified.contents))

        val (results, elapsedEval) = measureTimedValue {
            fhirOperator.evaluateLibrary(
                "http://localhost/Library/TestPass|1.0.0",
                patId(verified.contents),
                setOf("CompletedImmunization", "GetFinalDose", "GetSingleDose")
            ) as Parameters
        }

        println("TIME: Evaluated in $elapsedEval")

        assertEquals(QRDecoder.Status.ISSUER_NOT_TRUSTED, verified.status)

        assertEquals(true, results.getParameterBool("CompletedImmunization"))
        assertEquals(Collections.EMPTY_LIST, results.getParameters("GetFinalDose"))

        println(jsonParser.encodeResourceToString(verified.contents))

        val card2 = DDCCFormatter().run(verified.composition()!!)

        // Credential
        assertEquals("Yellow fever Vaccination", card2.cardTitle!!.split(" - ")[1])
        assertEquals(null, card2.validUntil)

        // Patient
        assertEquals("Aulo Agerio", card2.personName)
        assertEquals("Aug 23, 1905 - Male", card2.personDetails)
        assertEquals("ID: 16337361-9", card2.identifier)

        // Immunization
        assertEquals("Yellow fever vaccine", card2.vaccineType)
        assertEquals("25 (#123123123)", card2.vaccineInfo)

        // TODO: Fix these fields in the StructureMap
        assertEquals("Primary", card2.dose)
        //assertEquals("Jul 8, 2021", card2.doseDate)
        //assertEquals("Jul 22, 2021", card2.vaccineValid)
        assertEquals("Yellow fever", card2.vaccineAgainst)
        assertEquals(null, card2.vaccineInfo2)
        assertEquals(null, card2.location)
        assertEquals(null, card2.hcid)
        assertEquals(null, card2.pha)
        assertEquals(null, card2.hw)

        // Recommendation
        assertEquals(null, card2.nextDose)
    }

    @Ignore("QR codes created in the first version are now invalid")
    @Test
    fun viewWHOQR2() = runBlocking {
        val qr2 = open("WHOQR2Contents.txt")
        val verified = qrUnpacker.decode(qr2)

        loadBundle(verified.contents)

        val results = fhirOperator.evaluateLibrary(
            "http://localhost/Library/TestPass|1.0.0",
            patId(verified.contents),
            setOf("CompletedImmunization", "GetFinalDose", "GetSingleDose")) as Parameters

        assertEquals(QRDecoder.Status.VERIFIED, verified.status)

        assertEquals(true, results.getParameterBool("CompletedImmunization"))
        assertEquals(Collections.EMPTY_LIST, results.getParameters("GetFinalDose"))

        val card2 = DDCCFormatter().run(verified.composition()!!)

        // Credential
        assertEquals("COVID 19 Vaccination", card2.cardTitle!!.split(" - ")[1])
        assertEquals(null, card2.validUntil)

        // Patient
        assertEquals("EddieMurphy", card2.personName)
        assertEquals("Sep 19, 1986 - Male", card2.personDetails)
        assertEquals("ID: 111000111", card2.identifier)

        // Immunization
        assertEquals("SARSCoV2  mRNA vaccine", card2.vaccineType)
        assertEquals("Dose: 1", card2.dose)
        assertEquals(null, card2.doseDate)
        assertEquals(null, card2.vaccineValid)
        assertEquals("COVID 19", card2.vaccineAgainst)
        assertEquals("Lot #PT123F.9", card2.vaccineInfo)
        assertEquals(null, card2.vaccineInfo2)
        assertEquals("Location/971", card2.location)
        assertEquals("111000111", card2.hcid)
        assertEquals(null, card2.pha)
        assertEquals(null, card2.hw)

        // Recommendation
        assertEquals(null, card2.nextDose)
    }

    @Ignore("Not supported anymore")
    @Test
    fun viewSingaporePCR() = runBlocking {
        val qr2 = open("WHOSingaporePCRContents.txt")
        val verified = qrUnpacker.decode(qr2)

        assertEquals(QRDecoder.Status.NOT_SUPPORTED, verified.status)

        loadBundle(verified.contents)

        val results = fhirOperator.evaluateLibrary(
            "http://localhost/Library/TestPass|1.0.0",
            patId(verified.contents),
            setOf("CompletedImmunization", "GetFinalDose", "GetSingleDose")) as Parameters

        assertEquals(QRDecoder.Status.VERIFIED, verified.status)

        assertEquals(false, results.getParameterBool("CompletedImmunization"))
        assertEquals(Collections.EMPTY_LIST, results.getParameters("GetFinalDose"))

        val card2 = DDCCFormatter().run(verified.composition()!!)

        // Credential
        assertEquals("SARS-CoV-2 Test Result", card2.cardTitle!!.split(" - ")[1])
        assertEquals(null, card2.validUntil)

        // Patient
        assertEquals("TAN CHEN CHEN", card2.personName)
        assertEquals("Jan 15, 1990 - Male", card2.personDetails)
        assertEquals("ID: URN:UVCI:01:SG:ABC-CDE-CDE", card2.identifier)

        // Test Result
        assertEquals("Jul 27, 2022", card2.testDate)
        assertEquals("SARS-CoV-2 Test Result", card2.testType)
        assertEquals("Nucleic acid amplification with probe detection", card2.testTypeDetail)
        assertEquals("Not detected", card2.testResult)

        // Immunization
        assertEquals(null, card2.vaccineType)
        assertEquals(null, card2.dose)
        assertEquals(null, card2.doseDate)
        assertEquals(null, card2.vaccineValid)
        assertEquals(null, card2.vaccineAgainst)
        assertEquals(null, card2.vaccineInfo)
        assertEquals(null, card2.vaccineInfo2)
        assertEquals("SGP", card2.location)
        assertEquals(null, card2.hcid)
        assertEquals("Ministry of Health (MOH), Singapore [21M0386]", card2.pha)
        assertEquals(null, card2.hw)

        // Recommendation
        assertEquals(null, card2.nextDose)
    }

    @Test
    fun viewEUQR1() = runBlocking {
        val qr1 = open("EUQR1Contents.txt")
        val verified = qrUnpacker.decode(qr1)

        loadBundle(verified.contents)

        val results = fhirOperator.evaluateLibrary(
            "http://localhost/Library/TestPass|1.0.0",
            patId(verified.contents),
            setOf("CompletedImmunization", "GetFinalDose", "GetSingleDose")) as Parameters

        assertEquals(QRDecoder.Status.VERIFIED, verified.status)

        assertEquals(true, results.getParameterBool("CompletedImmunization"))
        assertNotEquals(Collections.EMPTY_LIST, results.getParameters("GetFinalDose"))

        val card2 = DDCCFormatter().run(verified.composition()!!)

        // Credential
        assertEquals("COVID-19 Vaccination", card2.cardTitle!!.split(" - ")[1])
        assertEquals("Use from Jun 30, 2021 to Jun 30, 2022", card2.validUntil)

        // Patient
        assertEquals("Monika Fellhauer, MONIKA FELLHAUER", card2.personName)
        assertEquals("Feb 24, 1984", card2.personDetails)
        assertEquals(null, card2.identifier)

        // Immunization
        assertEquals("SARS-CoV-2 mRNA Vaccine", card2.vaccineType)
        assertEquals("Dose: 2 of 2", card2.dose)
        assertEquals("May 27, 2021", card2.doseDate)
        assertEquals(null, card2.vaccineValid)
        assertEquals("COVID-19", card2.vaccineAgainst)
        assertEquals("EU/1/20/1528", card2.vaccineInfo)
        assertEquals("ORG-100030215", card2.vaccineInfo2)
        assertEquals("DE", card2.location)
        assertEquals(null, card2.hcid)
        assertEquals("Robert Koch-Institut", card2.pha)
        assertEquals(null, card2.hw)

        // Recommendation
        assertEquals(null, card2.nextDose)
    }

    @Test
    fun viewSHCQR1() = runBlocking {
        val qr1 = open("SHCQR1Contents.txt")
        val verified = qrUnpacker.decode(qr1)

        loadBundle(verified.contents)

        val results = fhirOperator.evaluateLibrary(
            "http://localhost/Library/TestPass|1.0.0",
            patId(verified.contents),
            setOf("CompletedImmunization", "GetFinalDose", "GetSingleDose")) as Parameters

        assertEquals(QRDecoder.Status.VERIFIED, verified.status)

        assertEquals(true, results.getParameterBool("CompletedImmunization"))
        assertEquals(Collections.EMPTY_LIST, results.getParameters("GetFinalDose"))

        val card2 = DDCCFormatter().run(verified.composition()!!)

        // Credential
        assertEquals("Vaccination", card2.cardTitle!!.split(" - ")[1])
        assertEquals("Use from Jun 1, 2021", card2.validUntil)

        // Patient
        assertEquals("John B. Anyperson", card2.personName)
        assertEquals("Jan 20, 1951", card2.personDetails)
        assertEquals(null, card2.identifier)

        // Immunization
        assertEquals("Moderna COVID-19", card2.vaccineType)
        assertEquals(null, card2.dose)
        assertEquals("Jan 1, 2021", card2.doseDate)
        assertEquals(null, card2.vaccineValid)
        assertEquals(null, card2.vaccineAgainst)
        assertEquals("Lot #0000001", card2.vaccineInfo)
        assertEquals(null, card2.vaccineInfo2)
        assertEquals(null, card2.location)
        assertEquals(null, card2.hcid)
        assertEquals(null, card2.pha)
        assertEquals("ABC General Hospital", card2.hw)

        // Recommendation
        assertEquals(null, card2.nextDose)
    }

    @Test
    fun viewSHCTestResults() = runBlocking {
        val qr1 = open("SHCTestResultsContents.txt")
        val verified = qrUnpacker.decode(qr1)

        assertEquals(QRDecoder.Status.VERIFIED, verified.status)

        val card2 = DDCCFormatter().run(verified.composition()!!)

        // Credential
        assertEquals("Test Result", card2.cardTitle!!.split(" - ")[1])
        assertEquals("Use from May 17, 2022", card2.validUntil)

        // Patient
        assertEquals("Joshua Mandel", card2.personName)
        assertEquals("Oct 26, 1982", card2.personDetails)
        assertEquals(null, card2.identifier)

        // Test Result
        assertEquals("May 17, 2022", card2.testDate)
        assertEquals("Test Result", card2.testType)
        assertEquals("SARS-CoV-2 (COVID19) RdRp gene [Presence] in Respiratory specimen by NAA with probe detection", card2.testTypeDetail)
        assertEquals("Negative", card2.testResult)

        // Immunization
        assertEquals(null, card2.vaccineType)
        assertEquals(null, card2.dose)
        assertEquals(null, card2.doseDate)
        assertEquals(null, card2.vaccineValid)
        assertEquals(null, card2.vaccineAgainst)
        assertEquals(null, card2.vaccineInfo)
        assertEquals(null, card2.vaccineInfo2)
        assertEquals(null, card2.location)
        assertEquals(null, card2.hcid)
        assertEquals(null, card2.pha)
        assertEquals(null, card2.hw)

        // Recommendation
        assertEquals(null, card2.nextDose)
    }

    @Test
    @Ignore("Keys are not in the trust registry anymore")
    fun viewDIVOCQR1() = runBlocking {
        TimeZone.setDefault(TimeZone.getTimeZone( "UTC"))

        val qr1 = open("DIVOCQR1Contents.txt")
        val verified = qrUnpacker.decode(qr1)

        loadBundle(verified.contents)

        val results = fhirOperator.evaluateLibrary(
            "http://localhost/Library/TestPass|1.0.0",
            patId(verified.contents),
            setOf("CompletedImmunization", "GetFinalDose", "GetSingleDose")) as Parameters

        assertEquals(QRDecoder.Status.VERIFIED, verified.status)

        assertEquals(false, results.getParameterBool("CompletedImmunization"))
        assertEquals(Collections.EMPTY_LIST, results.getParameters("GetFinalDose"))

        val card2 = DDCCFormatter().run(verified.composition()!!)

        // Credential
        assertEquals("COVID-19 Vaccination", card2.cardTitle!!.split(" - ")[1])
        assertEquals("Use from Mar 3, 2021", card2.validUntil)

        // Patient
        assertEquals("Third March User One", card2.personName)
        assertEquals("1956 - Male", card2.personDetails)
        assertEquals("ID: did:Passport:Dummy256", card2.identifier)

        // Immunization
        assertEquals("", card2.vaccineType)
        assertEquals("Dose: 1 of 2", card2.dose)
        assertEquals("Mar 3, 2021", card2.doseDate)
        assertEquals("Mar 3, 2021", card2.vaccineValid)
        assertEquals("COVID-19", card2.vaccineAgainst)
        assertEquals("COVISHIELD (#Dummy-TGN-Chamba)", card2.vaccineInfo)
        assertEquals(null, card2.vaccineInfo2)
        assertEquals("Himachal Site Name 176207", card2.location)
        assertEquals("39791185041847", card2.hcid)
        assertEquals(null, card2.pha)
        assertEquals("Dummy Vaccinator", card2.hw)

        // Recommendation
        assertEquals(null, card2.nextDose)
    }

    @Test
    fun viewDIVOCJamaica() = runBlocking {
        val qr1 = open("DIVOCJamaicaContents.txt")
        val verified = qrUnpacker.decode(qr1)

        loadBundle(verified.contents)

        val results = fhirOperator.evaluateLibrary(
            "http://localhost/Library/TestPass|1.0.0",
            patId(verified.contents),
            setOf("CompletedImmunization", "GetFinalDose", "GetSingleDose")) as Parameters

        assertEquals(QRDecoder.Status.INVALID_SIGNATURE, verified.status)

        assertEquals(true, results.getParameterBool("CompletedImmunization"))
        assertNotEquals(Collections.EMPTY_LIST, results.getParameters("GetFinalDose"))

        val card2 = DDCCFormatter().run(verified.composition()!!)

        // Credential
        assertEquals("COVID-19 Vaccination", card2.cardTitle!!.split(" - ")[1])
        assertEquals("Use from Dec 17, 2021", card2.validUntil)

        // Patient
        assertEquals("test user", card2.personName)
        assertEquals("Jan 5, 1998 - Male", card2.personDetails)
        assertEquals(null, card2.identifier)

        // Immunization
        assertEquals("COVID-19 vaccine, mRNA based vaccine", card2.vaccineType)
        assertEquals("Dose: 2 of 2", card2.dose)
        assertEquals("Apr 15, 2021", card2.doseDate)
        assertEquals("Apr 15, 2022", card2.vaccineValid)
        assertEquals("COVID-19", card2.vaccineAgainst)
        assertEquals("Pfizer (#JM4561)", card2.vaccineInfo)
        assertEquals(null, card2.vaccineInfo2)
        assertEquals("St, Jago Park Health Centre", card2.location)
        assertEquals("9874S1445691", card2.hcid)
        assertEquals(null, card2.pha)
        assertEquals("Sanderson, Brandon", card2.hw)

        // Recommendation
        assertEquals(null, card2.nextDose)
    }

    @Test
    fun viewICAOQR1() = runBlocking {
        val qr1 = open("ICAOAUQR1Contents.txt")
        val verified = qrUnpacker.decode(qr1)

        loadBundle(verified.contents)

        val results = fhirOperator.evaluateLibrary(
            "http://localhost/Library/TestPass|1.0.0",
            patId(verified.contents),
            setOf("CompletedImmunization", "GetFinalDose", "GetSingleDose")) as Parameters

        assertEquals(QRDecoder.Status.VERIFIED, verified.status)

        assertEquals(true, results.getParameterBool("CompletedImmunization"))
        assertEquals(Collections.EMPTY_LIST, results.getParameters("GetFinalDose"))

        val card2 = DDCCFormatter().run(verified.composition()!!)

        // Credential
        assertEquals("COVID-19 Vaccination", card2.cardTitle!!.split(" - ")[1])
        assertEquals(null, card2.validUntil)

        // Patient
        assertEquals("CITIZEN  JANE SUE", card2.personName)
        assertEquals("May 15, 1961 - Female", card2.personDetails)
        assertEquals("ID: PA0941262", card2.identifier)

        // Immunization
        assertEquals("COVID-19 vaccines", card2.vaccineType)
        assertEquals("Dose: 1", card2.dose)
        assertEquals("Sep 15, 2021", card2.doseDate)
        assertEquals(null, card2.vaccineValid)
        assertEquals("COVID-19", card2.vaccineAgainst)
        assertEquals("AstraZeneca Vaxzevria (#300157P)", card2.vaccineInfo)
        assertEquals(null, card2.vaccineInfo2)
        assertEquals("AUS", card2.location)
        assertEquals("VB0009990012", card2.hcid)
        assertEquals("General Practitioner", card2.pha)
        assertEquals(null, card2.hw)

        // Recommendation
        assertEquals(null, card2.nextDose)
    }
}