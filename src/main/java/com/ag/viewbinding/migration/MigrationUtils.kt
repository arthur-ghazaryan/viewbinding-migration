package com.ag.viewbinding.migration

import com.intellij.openapi.module.ModuleServiceManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.android.synthetic.AndroidConst
import org.jetbrains.kotlin.android.synthetic.res.AndroidLayoutXmlFileManager
import org.jetbrains.kotlin.android.synthetic.res.AndroidVariantData
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.idea.caches.project.getModuleInfo
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.core.unwrapModuleSourceInfo
import org.jetbrains.kotlin.idea.intentions.callExpression
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe

internal fun getSyntheticElements(psiElement: PsiElement) =
    PsiTreeUtil.collectElements(psiElement) { it is KtNameReferenceExpression }.filter {
        (it as KtNameReferenceExpression).getReferencedNameElement().getModuleInfo().findAndroidModuleInfo()

        val layoutManager = getLayoutManager(it) ?: return@filter false
        val propertyDescriptor = resolvePropertyDescriptor(it) ?: return@filter false

        val psiElements = layoutManager.propertyToXmlAttributes(propertyDescriptor)
        val valueElements = psiElements.mapNotNull { (it as? XmlAttribute)?.valueElement as? PsiElement }
        return@filter valueElements.isNotEmpty() //filtering expressions which refer to xml aka synthetic accessors
    }

internal fun resolvePropertyDescriptor(simpleNameExpression: KtSimpleNameExpression): PropertyDescriptor? {
    val resolvedCall = simpleNameExpression.resolveToCall()
    return resolvedCall?.resultingDescriptor as? PropertyDescriptor
}

internal fun getLayoutManager(sourceElement: PsiElement): AndroidLayoutXmlFileManager? {
    val moduleInfo = sourceElement.getModuleInfo().findAndroidModuleInfo() ?: return null
    return ModuleServiceManager.getService(moduleInfo.module, AndroidLayoutXmlFileManager::class.java)
}

fun ModuleInfo.findAndroidModuleInfo() = unwrapModuleSourceInfo()?.takeIf { it.platform.isJvm() }

internal fun getLayoutFiles(
    propertyDescriptor: PropertyDescriptor,
    layoutManager: AndroidLayoutXmlFileManager
): List<PsiFile> {
    val fqPath = propertyDescriptor.fqNameUnsafe.pathSegments()
    if (fqPath.size <= AndroidConst.SYNTHETIC_PACKAGE_PATH_LENGTH) return emptyList()

    fun handle(variantData: AndroidVariantData, defaultVariant: Boolean = false): List<PsiFile> {
        val layoutNamePosition = AndroidConst.SYNTHETIC_PACKAGE_PATH_LENGTH + (if (defaultVariant) 0 else 1)
        val layoutName = fqPath[layoutNamePosition].asString()

        val layoutFiles = variantData.layouts[layoutName] ?: return emptyList()
        if (layoutFiles.isEmpty()) return emptyList()

        return layoutFiles
    }

    layoutManager.getModuleData().variants.forEach { variantData ->
        if (variantData.variant.isMainVariant && fqPath.size == AndroidConst.SYNTHETIC_PACKAGE_PATH_LENGTH + 2) {
            handle(variantData).let { return it }
        } else if (fqPath[AndroidConst.SYNTHETIC_PACKAGE_PATH_LENGTH].asString() == variantData.variant.name) {
            handle(variantData).let { return it }
        }
    }

    return emptyList()
}

internal fun replaceWithSafeCall(
    exprDot: KtDotQualifiedExpression,
    replacedReceiver: PsiElement
): PsiElement? {
    val call = exprDot.callExpression ?: exprDot.selectorExpression ?: return null
    val safeQualifiedExpression = KtPsiFactory(call).createExpressionByPattern(
        "$0?.$1", exprDot.receiverExpression.replace(replacedReceiver), call,
        reformat = false
    )
    return exprDot.replace(safeQualifiedExpression)
}

internal fun String.toCamelCase(): String {
    val split = this.split("_")
    if (split.isEmpty()) return ""
    if (split.size == 1) return split[0].capitalize()
    return split.joinToCamelCase()
}

private fun List<String>.joinToCamelCase(): String = when (size) {
    0 -> throw IllegalArgumentException("invalid section size, cannot be zero")
    1 -> this[0].toCamelCase()
    else -> this.joinToString("") { it.toCamelCase() }
}

internal fun String.toCamelCaseAsVar(): String {
    val split = this.split("_")
    if (split.isEmpty()) return ""
    if (split.size == 1) return split[0]
    return split.joinToCamelCaseAsVar()
}

internal fun List<String>.joinToCamelCaseAsVar(): String = when (size) {
    0 -> throw IllegalArgumentException("invalid section size, cannot be zero")
    1 -> this[0].toCamelCaseAsVar()
    else -> get(0).toCamelCaseAsVar() + drop(1).joinToCamelCase()
}

internal fun KtClass.packageName(): String {
    return containingKtFile.importDirectives.find {
        it.importedFqName?.asString()?.endsWith("R") == true
    }?.importedFqName?.parent()?.asString() ?: containingKtFile.packageFqName.asString()
}
