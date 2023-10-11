// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.angular2.inspections.quickfixes

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.template.Expression
import com.intellij.codeInsight.template.Result
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.lang.ecmascript6.psi.impl.ES6ImportPsiUtil
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.lang.javascript.validation.fixes.CreateJSVariableIntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.refactoring.suggested.createSmartPointer
import org.angular2.entities.Angular2ComponentLocator
import org.angular2.inspections.quickfixes.Angular2FixesTemplateUtil.addClassMemberModifiers
import org.angular2.lang.Angular2Bundle
import org.angular2.signals.Angular2SignalUtils

class CreateComponentSignalIntentionAction(methodExpression: JSReferenceExpression)
  : CreateJSVariableIntentionAction(methodExpression.referenceName, true, false, false) {

  private val myRefExpressionPointer: SmartPsiElementPointer<JSReferenceExpression> = methodExpression.createSmartPointer()

  override fun applyFix(project: Project, psiElement: PsiElement, file: PsiFile, editor: Editor?) {
    val componentClass = Angular2ComponentLocator.findComponentClass(psiElement)!!
    doApplyFix(project, componentClass, componentClass.containingFile, null)
  }

  override fun getName(): String {
    return Angular2Bundle.message("angular.quickfix.template.create-signal.name", myReferencedName)
  }

  override fun getPriority(): PriorityAction.Priority {
    return PriorityAction.Priority.TOP
  }

  override fun beforeStartTemplateAction(referenceExpression: JSReferenceExpression,
                                         editor: Editor,
                                         anchor: PsiElement,
                                         isStaticContext: Boolean): JSReferenceExpression {
    return referenceExpression
  }

  override fun skipParentIfClass(): Boolean {
    return false
  }

  override fun calculateAnchors(psiElement: PsiElement): Pair<JSReferenceExpression, PsiElement> {
    return Pair.create(myRefExpressionPointer.element, psiElement.lastChild)
  }

  override fun addAccessModifier(template: Template,
                                 referenceExpression: JSReferenceExpression,
                                 staticContext: Boolean,
                                 targetClass: JSClass) {
    addClassMemberModifiers(template, staticContext, targetClass)
  }

  override fun buildTemplate(template: Template,
                             referenceExpression: JSReferenceExpression?,
                             isStaticContext: Boolean,
                             anchorParent: PsiElement) {
    template.addTextSegment(myReferencedName)
    template.addTextSegment(" = signal<")

    val types = guessTypesForExpression(referenceExpression, anchorParent, false)
      .map { if (!it.matches(Regex("\\|\\s*null($|\\s*\\|)"))) "$it | null" else it }
    if (types.isEmpty()) {
      addCompletionVar(template)
    }
    else {
      val expression: Expression = if (types.size == 1)
        ConstantNode(types[0])
      else
        ConstantNode(null as Result?).withLookupStrings(types)
      template.addVariable("__type" + referenceExpression!!.getText(), expression, expression, true)
    }
    template.addTextSegment(">(")

    val expression: Expression = ConstantNode("null")
    template.addVariable("\$INITIAL_VALUE$", expression, expression, true)
    template.addTextSegment(")")
    addSemicolonSegment(template, anchorParent)

    ES6ImportPsiUtil.insertJSImport(anchorParent, Angular2SignalUtils.SIGNAL_FUNCTION,
                                    Angular2SignalUtils.signalFunction(anchorParent) ?: return, null)
  }
}