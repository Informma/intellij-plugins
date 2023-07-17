package com.intellij.pp.lang.parser

import com.intellij.lang.PsiBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * Implementations can be added to the [PpBuildAdapter] to parse preprocessor
 * statements.
 */
interface PpStatementParser {
    /**
     * Parses one statement. Should return true if any token was consumed
     * whether the statement was parsed successful or not.
     */
    fun parseStatement(tokenType: IElementType, builderFactory: () -> PsiBuilder): Boolean

    /**
     * The token types of the statements which can be generated by
     * [parseStatement]. Used for recognizing preprocessor statements in the
     * psi tree.
     */
    fun getStatementTokens(): TokenSet
}