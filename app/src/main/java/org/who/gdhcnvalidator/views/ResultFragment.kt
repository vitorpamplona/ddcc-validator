package org.who.gdhcnvalidator.views

import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import kotlinx.coroutines.*
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.who.gdhcnvalidator.FhirApplication
import org.who.gdhcnvalidator.QRDecoder
import org.who.gdhcnvalidator.R
import org.who.gdhcnvalidator.databinding.FragmentResultBinding
import org.who.gdhcnvalidator.services.DDCCFormatter
import org.who.gdhcnvalidator.trust.TrustRegistry
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue


/**
 * Displays a Verifiable Credential after being Scanned by the QRScan Fragment
 */
class ResultFragment : Fragment() {
    private var binding: FragmentResultBinding? = null
    private val args: ResultFragmentArgs by navArgs()

    private val statuses = mapOf(
        QRDecoder.Status.NOT_FOUND to R.string.verification_status_not_found,
        QRDecoder.Status.NOT_SUPPORTED to R.string.verification_status_invalid_base45,
        QRDecoder.Status.INVALID_ENCODING to R.string.verification_status_invalid_base45,
        QRDecoder.Status.INVALID_COMPRESSION to R.string.verification_status_invalid_zip,
        QRDecoder.Status.INVALID_SIGNING_FORMAT to R.string.verification_status_invalid_cose,
        QRDecoder.Status.KID_NOT_INCLUDED to R.string.verification_status_kid_not_included,
        QRDecoder.Status.ISSUER_NOT_TRUSTED to R.string.verification_status_issuer_not_trusted,
        QRDecoder.Status.TERMINATED_KEYS to R.string.verification_status_terminated_keys,
        QRDecoder.Status.EXPIRED_KEYS to R.string.verification_status_expired_keys,
        QRDecoder.Status.REVOKED_KEYS to R.string.verification_status_revoked_keys,
        QRDecoder.Status.INVALID_SIGNATURE to R.string.verification_status_invalid_signature,
        QRDecoder.Status.VERIFIED to R.string.verification_status_verified,
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        binding = FragmentResultBinding.inflate(inflater, container, false)
        return binding!!.root
    }

    private fun setTextView(view: TextView?, text: String?, line: View?) {
        if (text != null && text.isNotEmpty()) {
            view?.text = text
            line?.visibility = TextView.VISIBLE
        } else
            line?.visibility = TextView.GONE
    }

    data class ResultCard(
        val hcid: String?,
        val cardTitle: String?,
        val validUntil: String?,

        val personName: String?,
        val personDetails: String?,
        val identifier: String?,

        val location: String?,
        val pha: String?,
        val hw: String?,

        // testresults
        val testType: String?,
        val testTypeDetail: String?,
        val testDate: String?,
        val testResult: String?,

        // immunization
        val dose: String?,
        val doseDate: String?,
        val vaccineValid: String?,
        val vaccineAgainst: String?,
        val vaccineType: String?,
        val vaccineInfo: String?,
        val vaccineInfo2: String?,

        // recommendations
        val nextDose: String?,
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding?.tvResultHeader?.visibility = TextView.INVISIBLE
        binding?.tvResultCard?.visibility = TextView.INVISIBLE

        if (args.qr != null) {
            resolveAndShowQR(args.qr!!)
        }

        binding?.btResultClose?.setOnClickListener {
            findNavController().navigate(R.id.action_ResultFragment_to_HomeFragment)
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    private fun updateScreen(DDCC: QRDecoder.VerificationResult) {
        binding?.tvResultHeader?.visibility = TextView.VISIBLE

        statuses[DDCC.status]?.let {
            binding?.tvResultTitle?.text = resources.getString(it)
        }

        if (DDCC.status == QRDecoder.Status.VERIFIED) {
            if (DDCC.issuer!!.scope == TrustRegistry.Scope.PRODUCTION) {
                binding?.tvResultHeader?.background = resources.getDrawable(R.drawable.rounded_pill, null)
                binding?.tvResultTitle?.text = resources.getString(R.string.verification_status_verified)
            } else {
                binding?.tvResultHeader?.background = resources.getDrawable(R.drawable.rounded_pill_test, null)
                binding?.tvResultTitle?.text = resources.getString(R.string.verification_status_verified_test_scope)
            }
            binding?.tvResultTitleIcon?.setImageResource(R.drawable.check_circle_24px)
        } else {
            binding?.tvResultHeader?.background = resources.getDrawable(R.drawable.rounded_pill_invalid, null)
            binding?.tvResultTitleIcon?.setImageResource(R.drawable.cancel_24px)
        }

        if (DDCC.issuer != null) {
            binding?.tvResultSignedBy?.text = "Signed by ${DDCC.issuer!!.displayName["en"]}"
            if (DDCC.issuer!!.scope == TrustRegistry.Scope.PRODUCTION) {
                binding?.tvResultSignedByIconFailed?.visibility = View.GONE
                binding?.tvResultSignedByIconUntrusted?.visibility = View.GONE
                binding?.tvResultSignedByIconSuccess?.visibility = View.VISIBLE
            } else {
                binding?.tvResultSignedByIconFailed?.visibility = View.GONE
                binding?.tvResultSignedByIconUntrusted?.visibility = View.VISIBLE
                binding?.tvResultSignedByIconSuccess?.visibility = View.GONE
            }
        } else {
            binding?.tvResultSignedBy?.text = resources.getString(R.string.verification_status_invalid_signature)
            binding?.tvResultSignedByIconFailed?.visibility = View.VISIBLE
            binding?.tvResultSignedByIconUntrusted?.visibility = View.GONE
            binding?.tvResultSignedByIconSuccess?.visibility = View.GONE
        }

        if (DDCC.contents != null) {
            binding?.tvResultCard?.visibility = TextView.VISIBLE

            val card = DDCCFormatter().run(DDCC.composition()!!)

            // Credential
            setTextView(binding?.tvResultScanDate, card.cardTitle, binding?.tvResultScanDate)
            setTextView(binding?.tvResultValidUntil, card.validUntil, binding?.llResultValidUntil)

            // Patient
            setTextView(binding?.tvResultName, card.personName, binding?.tvResultName)
            setTextView(binding?.tvResultPersonDetails, card.personDetails, binding?.tvResultPersonDetails)
            setTextView(binding?.tvResultIdentifier, card.identifier, binding?.tvResultIdentifier)

            // Location, Practice, Practitioner
            setTextView(binding?.tvResultHcid, card.hcid, binding?.llResultHcid)
            setTextView(binding?.tvResultPha, card.pha, binding?.llResultPha)
            setTextView(binding?.tvResultHw, card.hw, binding?.llResultHw)

            // Test Result
            setTextView(binding?.tvResultTestType, card.testType, binding?.tvResultTestType)
            setTextView(binding?.tvResultTestTypeDetail, card.testTypeDetail, binding?.llResultTestTypeDetail)
            setTextView(binding?.tvResultTestDate, card.testDate, binding?.llResultTestDate)
            setTextView(binding?.tvResultTestTitle, card.testResult, binding?.tvResultTestTitle)

            // Immunization
            setTextView(binding?.tvResultVaccineType, card.vaccineType, binding?.tvResultVaccineType)
            setTextView(binding?.tvResultDoseTitle, card.dose, binding?.tvResultDoseTitle)
            setTextView(binding?.tvResultDoseDate, card.doseDate, binding?.llResultDoseDate)
            setTextView(binding?.tvResultVaccineValid, card.vaccineValid, binding?.llResultVaccineValid)
            setTextView(binding?.tvResultVaccineInfo, card.vaccineInfo, binding?.llResultVaccineInfo)
            setTextView(binding?.tvResultVaccineInfo2, card.vaccineInfo2, binding?.llResultVaccineInfo2)
            setTextView(binding?.tvResultCentre, card.location, binding?.llResultCentre)

            // Recommendation
            setTextView(binding?.tvResultNextDose, card.nextDose, binding?.llResultNextDose)

            // Status
            binding?.llResultStatus?.removeAllViews()
        }
    }

    private fun resolveAndShowQR(qr: String) = runBlocking {
        CoroutineScope(Dispatchers.Main + Job()).launch {
            withContext(Dispatchers.IO) {
                val result = resolveQR(qr)

                withContext(Dispatchers.Main){
                    updateScreen(result)
                }

                val bundle = result.contents

                if (bundle != null) {
                    saveContents(bundle)
                    computeAndShowStatus(patId(bundle))
                }
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    fun computeAndShowStatus(patientId: String) = runBlocking {
        CoroutineScope(Dispatchers.Main + Job()).launch {
            withContext(Dispatchers.IO) {
                val results = context?.let {
                    FhirApplication.subscribedIGs(it).associate {
                        val (status, elapsed) = measureTimedValue {
                            resolveStatus(patientId, it.url, "Completed Immunization")
                        }
                        println("TIME: Evaluation of ${it.url} in $elapsed")
                        Pair(it.name, status)
                    }
                }

                if (results != null) {
                    withContext(Dispatchers.Main) {
                        binding?.let {
                            results.forEach {
                                addResultStatus(it.key, it.value)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun addResultStatus(ruleName: String, result: Boolean?) {
        binding?.llResultStatus?.addView(TextView(context).apply {
            text = when (result) {
                true -> "${ruleName}: COVID Safe"
                false -> "${ruleName}: COVID Vulnerable"
                null -> "${ruleName}: Unable to evaluate"
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
    }

    private fun patId(bundle: org.hl7.fhir.r4.model.Bundle): String {
        return bundle.entry.filter { it.resource is Patient }.first().resource.id.removePrefix("Patient/")
    }

    private suspend fun saveContents(bundle: org.hl7.fhir.r4.model.Bundle) {
        for (entry in bundle.entry) {
            FhirApplication.fhirEngine(requireContext()).create(entry.resource)
        }
    }

    private fun resolveQR(qr: String): QRDecoder.VerificationResult {
        return QRDecoder(FhirApplication.trustRegistry(requireContext())).decode(qr)
    }

    private fun resolveStatus(patientId: String, libUrl: String, funcName: String): Boolean? {
        // Might be slow
        return try {
            val results = FhirApplication.fhirOperator(requireContext()).evaluateLibrary(
                libUrl,
                patientId,
                setOf(funcName)) as Parameters

            results.getParameterBool(funcName)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }
}