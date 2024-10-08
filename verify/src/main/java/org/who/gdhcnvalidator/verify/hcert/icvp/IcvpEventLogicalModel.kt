package org.who.gdhcnvalidator.verify.hcert.icvp

import org.hl7.fhir.r4.model.*
import java.sql.Ref

open class IcvpEventLogicalModel(
    name: StringType?,
    dob: DateType?,
    sex: CodeType?,
    nationality: CodeType?,

    nid: StringType?,
    guardian: StringType?,

    issuer: Reference?,
    vaccineDetail: DvcVaccineDetails
): DvcLogicalModel(name, dob, sex, nationality, nid, guardian, issuer, vaccineDetail)