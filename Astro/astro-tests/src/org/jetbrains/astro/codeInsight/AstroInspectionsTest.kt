package org.jetbrains.astro.codeInsight

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.typescript.inspections.TypeScriptUnresolvedReferenceInspection
import org.jetbrains.astro.AstroBundle
import org.jetbrains.astro.AstroCodeInsightTestCase
import org.jetbrains.astro.inspections.AstroMissingComponentImportInspection
import kotlin.reflect.KClass

class AstroInspectionsTest : AstroCodeInsightTestCase() {

  fun testMissingComponentImport() = doTest(AstroMissingComponentImportInspection::class,
                                            AstroBundle.message("astro.quickfix.import.component.name", "Component"),
                                            additionalFiles = listOf("Component.astro"))

  fun testMissingTsSymbolImport() = doTest(TypeScriptUnresolvedReferenceInspection::class,
                                           "Insert 'import {Colors} from \"./colors\"'",
                                           additionalFiles = listOf("colors.ts")
  )

  //region Test configuration and helper methods

  override fun getBasePath(): String = "codeInsight/inspections"

  private fun doTest(inspection: KClass<out LocalInspectionTool>,
                     quickFixName: String? = null,
                     additionalFiles: List<String> = emptyList()) {
    myFixture.enableInspections(inspection.java)
    configure(additionalFiles = additionalFiles)
    myFixture.checkHighlighting()
    if (quickFixName == null) {
      return
    }
    myFixture.launchAction(myFixture.findSingleIntention(quickFixName))
    myFixture.checkResultByFile(getTestName(true) + "_after.astro")
  }

  //endregion
}