package com.ag.viewbinding.migration

import org.jetbrains.kotlin.psi.KtClass

internal fun KtClass.removeSyntheticImports() {
    containingKtFile.importDirectives.forEach {
        if (it.text.contains("kotlinx.android.synthetic.main")) {
            it.delete()
        }
    }
}