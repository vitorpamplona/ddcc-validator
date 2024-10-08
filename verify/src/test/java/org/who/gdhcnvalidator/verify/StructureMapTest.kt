package org.who.gdhcnvalidator.verify

import com.google.common.truth.Truth.assertThat
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.StringType
import org.junit.Test

class StructureMapTest {
    class MyDCCLogicalModel(
        var givenName: StringType? = null,
        var familyName: StringType? = null
    ): BaseModel()

    @Test
    fun testStructureMapOfCustomeModelIntoFhir() {
        val mapping =
            """map "http://hl7.org/fhir/StructureMap/PatientRegistration" = 'PatientRegistration'

        uses "http://hl7.org/fhir/StructureDefinition/MyDCCLogicalModel" as source
        uses "http://hl7.org/fhir/StructureDefinition/Bundle" as target
        
        group PatientRegistration(source src : MyDCCLogicalModel, target bundle: Bundle) {
            src -> bundle.entry as entry, entry.resource = create('Patient') as patient then
                ExtractPatient(src, patient) "Patient";
        }
        
        group ExtractPatient(source src : MyDCCLogicalModel, target tgt : Patient) {
             src -> tgt.name = create('HumanName') as humanName then 
                ExtractHumanName(src, humanName) "Human Name";
        }
        
        group ExtractHumanName(source src : StringType, target tgt : HumanName) {
             src.givenName as givenName -> tgt.given = givenName "Given Name";
             src.familyName as familyName -> tgt.family = familyName "Family Name";
        }"""

        val logicalObject = MyDCCLogicalModel(StringType("John"), StringType("Smith"))
        val mapper = BaseMapper()
        mapper.addCache("TestMap.map", mapping)

        val bundle = BaseMapper().run(logicalObject, "TestMap.map")

        val patient = bundle.entry.get(0).resource as Patient
        assertThat(patient.active).isFalse()
        assertThat(patient.name.first().given.first().toString()).isEqualTo("John")
        assertThat(patient.name.first().family.toString()).isEqualTo("Smith")
    }
}