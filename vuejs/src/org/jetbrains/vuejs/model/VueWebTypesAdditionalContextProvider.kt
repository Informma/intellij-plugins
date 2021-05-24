// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.model

import com.intellij.javascript.web.webTypes.registry.WebTypesAdditionalContextProvider
import com.intellij.javascript.web.webTypes.registry.WebTypesContribution
import com.intellij.javascript.web.webTypes.registry.WebTypesContribution.Companion.KIND_HTML_ATTRIBUTES
import com.intellij.javascript.web.webTypes.registry.WebTypesContribution.Companion.KIND_HTML_ELEMENTS
import com.intellij.javascript.web.webTypes.registry.WebTypesContribution.Companion.KIND_HTML_EVENTS
import com.intellij.javascript.web.webTypes.registry.WebTypesContribution.Companion.KIND_HTML_SLOTS
import com.intellij.javascript.web.webTypes.registry.WebTypesContribution.Companion.KIND_HTML_VUE_DIRECTIVES
import com.intellij.javascript.web.webTypes.registry.WebTypesContribution.Companion.VUE_FRAMEWORK
import com.intellij.javascript.web.webTypes.registry.WebTypesContributionsContainer
import com.intellij.javascript.web.webTypes.registry.WebTypesNameMatchQueryParams
import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.javascript.psi.stubs.JSImplicitElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.Stack
import org.jetbrains.vuejs.codeInsight.documentation.VueDocumentedItem
import org.jetbrains.vuejs.codeInsight.fromAsset

class VueWebTypesAdditionalContextProvider : WebTypesAdditionalContextProvider {

  override fun getAdditionalContext(element: PsiElement?, framework: String?): List<WebTypesContributionsContainer> =
    element
      ?.takeIf { framework == VUE_FRAMEWORK }
      ?.let { VueModelManager.findEnclosingContainer(it) }
      ?.let { listOf(EntityContainerWrapper(element.containingFile, it)) }
    ?: emptyList()

  private abstract class VueWrapperBase : WebTypesContributionsContainer,
                                          WebTypesContributionsContainer.WebTypesContext {
    val context: WebTypesContributionsContainer.WebTypesContext
      get() = this

    val root: WebTypesContributionsContainer.ContributionRoot
      get() = WebTypesContributionsContainer.ContributionRoot.HTML

    override val framework: String
      get() = VUE_FRAMEWORK

    override val packageName: String
      get() = "Vue project source"

    override val version: String?
      get() = null
  }

  private class EntityContainerWrapper(private val containingFile: PsiFile,
                                       private val container: VueEntitiesContainer) : VueWrapperBase() {

    override fun getContributions(root: WebTypesContributionsContainer.ContributionRoot?,
                                  kind: String,
                                  name: String?,
                                  params: WebTypesNameMatchQueryParams,
                                  context: Stack<WebTypesContributionsContainer>): Sequence<WebTypesContributionsContainer> =
      if (root == null || root == WebTypesContributionsContainer.ContributionRoot.HTML)
        when (kind) {
          KIND_HTML_ELEMENTS -> {
            val result = mutableListOf<VueComponent>()
            val normalizedTagName = name?.let { fromAsset(it) }
            container.acceptEntities(object : VueModelProximityVisitor() {
              override fun visitComponent(name: String, component: VueComponent, proximity: Proximity): Boolean {
                return acceptSameProximity(proximity, normalizedTagName == null || fromAsset(name) == normalizedTagName) {
                  // Cannot self refer without export declaration with component name
                  if ((component.source as? JSImplicitElement)?.context != containingFile) {
                    result.add(component)
                  }
                }
              }
            }, VueModelVisitor.Proximity.GLOBAL)
            result.asSequence().mapNotNull {
              ComponentWrapper(name ?: it.defaultName ?: return@mapNotNull null, it)
            }
          }
          KIND_HTML_VUE_DIRECTIVES -> {
            val searchName = name?.let { fromAsset(it) }
            val directives = mutableListOf<VueDirective>()
            container.acceptEntities(object : VueModelProximityVisitor() {
              override fun visitDirective(name: String, directive: VueDirective, proximity: Proximity): Boolean {
                return acceptSameProximity(proximity, searchName == null || fromAsset(name) == searchName) {
                  directives.add(directive)
                }
              }
            }, VueModelVisitor.Proximity.GLOBAL)
            directives.asSequence().mapNotNull {
              DirectiveWrapper(name ?: it.defaultName ?: return@mapNotNull null, it)
            }
          }
          else -> emptySequence()
        }
      else emptySequence()

    override fun getModificationCount(): Long =
      PsiModificationTracker.SERVICE.getInstance(containingFile.project).modificationCount

  }

  private abstract class DocumentedItemWrapper<T : VueDocumentedItem>(
    override val matchedName: String, protected val item: T) : VueWrapperBase(), WebTypesContribution {

    override val kind: String get() = KIND_HTML_ELEMENTS

    override val description: String?
      get() = item.documentation.description

    override val docUrl: String?
      get() = item.documentation.docUrl
  }

  private abstract class NamedSymbolWrapper<T : VueNamedSymbol>(item: T, matchedName: String = item.name) : DocumentedItemWrapper<T>(
    matchedName, item) {
    override val name: String
      get() = item.name

    override val source: PsiElement?
      get() = item.source
  }

  private class ComponentWrapper(matchedName: String, component: VueComponent) :
    DocumentedItemWrapper<VueComponent>(matchedName, component) {

    override val name: String
      get() = item.defaultName ?: matchedName

    override val source: PsiElement?
      get() = item.source

    override fun getContributions(root: WebTypesContributionsContainer.ContributionRoot?,
                                  kind: String,
                                  name: String?,
                                  params: WebTypesNameMatchQueryParams,
                                  context: Stack<WebTypesContributionsContainer>): Sequence<WebTypesContributionsContainer> =
      if (root == null || root == WebTypesContributionsContainer.ContributionRoot.HTML)
        when (kind) {
          KIND_HTML_ATTRIBUTES -> {
            val searchName = name?.let { fromAsset(it) }
            val props = mutableListOf<VueInputProperty>()
            item.acceptPropertiesAndMethods(object : VueModelVisitor() {
              override fun visitInputProperty(prop: VueInputProperty, proximity: Proximity): Boolean {
                if (searchName == null || fromAsset(prop.name) == searchName) {
                  props.add(prop)
                }
                return true
              }
            })
            props.asSequence().map { InputPropWrapper(name ?: it.name, it) }
          }
          KIND_HTML_EVENTS -> {
            (item as? VueContainer)?.emits?.asSequence()?.map { EmitCallWrapper(it) } ?: emptySequence()
          }
          KIND_HTML_SLOTS -> {
            (item as? VueContainer)?.slots?.asSequence()?.map { SlotWrapper(it) } ?: emptySequence()
          }
          else -> emptySequence()
        }
      else emptySequence()

  }

  private class InputPropWrapper(matchedName: String, property: VueInputProperty)
    : NamedSymbolWrapper<VueInputProperty>(property, matchedName) {

    override val jsType: JSType?
      get() = item.jsType
  }

  private class EmitCallWrapper(emitCall: VueEmitCall) : NamedSymbolWrapper<VueEmitCall>(emitCall) {
    override val jsType: JSType?
      get() = item.eventJSType
  }

  private class SlotWrapper(slot: VueSlot) : NamedSymbolWrapper<VueSlot>(slot) {
    override val jsType: JSType?
      get() = item.scope
  }

  private class DirectiveWrapper(matchedName: String, directive: VueDirective) :
    DocumentedItemWrapper<VueDirective>(matchedName, directive) {

    override val name: String
      get() = item.defaultName ?: matchedName

    override val source: PsiElement?
      get() = item.source

  }

}