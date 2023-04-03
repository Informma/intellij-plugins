// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.angular2.lang.html

import com.intellij.lang.javascript.psi.JSControlFlowScope
import com.intellij.lang.javascript.psi.controlflow.JSControlFlowService
import com.intellij.lang.typescript.psi.impl.TypeScriptConfigRedirectingFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.source.html.HtmlFileImpl
import org.angular2.entities.Angular2ComponentLocator

class Angular2HtmlFile(viewProvider: FileViewProvider) : HtmlFileImpl(viewProvider, Angular2HtmlFileElementType.INSTANCE),
                                                         JSControlFlowScope, TypeScriptConfigRedirectingFile {

  override fun isTopmostControlFlowScope() = true

  override fun subtreeChanged() {
    super.subtreeChanged()
    JSControlFlowService.getService(project).resetControlFlow(this)
  }

  override fun redirectToConfigIncludedFile(): PsiFile {
    return Angular2ComponentLocator.findComponentClass(this)?.containingFile ?: this
  }
}
