package com.ag.viewbinding.migration

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiElementFilter
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.idea.core.ShortenReferences
import org.jetbrains.kotlin.idea.formatter.commitAndUnblockDocument
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.psi.*

private fun KtClass.findOnViewCreated(): PsiElement? {
    return body?.functions?.find { it.text.contains("onViewCreated") }
}

fun KtClass.addBindingProperty(ktPsiFactory: KtPsiFactory, name: String, notNull: Boolean) {
    val bindingProp = ktPsiFactory.createProperty(
        "private",
        if (notNull) "_binding" else "binding",
        packageName() + ".databinding." + name.toCamelCase().plus("Binding").plus("?"),
        true,
        "null"
    )
    if (body?.properties?.isEmpty() == false) {
        body?.properties?.last()
            ?.add(bindingProp)
    } else {
        val firstFunction = body?.functions?.get(0)
        body?.addBefore(
            bindingProp,
            firstFunction
        )
    }
    if (notNull) {
        body?.properties?.last()?.add(
            ktPsiFactory.createProperty(
                "private",
                "binding",
                packageName() + ".databinding." + name.toCamelCase().plus("Binding"),
                false,
                null
            )
        )
        body?.properties?.last()?.add(
            ktPsiFactory.createPropertyGetter(
                ktPsiFactory.createExpression("_binding!!")
            )
        )
    }
}

fun KtFunction.insertBindingInitializer(ktPsiFactory: KtPsiFactory, name: String, fragment: Boolean, notNull: Boolean) {
    val expr = if (fragment) {
        ktPsiFactory.createExpression("${if (notNull) "_" else ""}binding = ${name.toCamelCase()}Binding.bind(view)")
    } else {
        ktPsiFactory.createExpression("${if (notNull) "_" else ""}binding = ${name.toCamelCase()}Binding.inflater(layoutInflater)")
    }
    val firstChild = bodyBlockExpression?.statements?.first()
    bodyBlockExpression?.addBefore(expr, firstChild)
    bodyBlockExpression?.addBefore(ktPsiFactory.createNewLine(), firstChild)
}

fun KtClass.removeBindingInsideOnDestroyView(ktPsiFactory: KtPsiFactory, notNull: Boolean) {
    val onDestroyView = body?.functions?.find { it.name == "onDestroyView" }?.bodyBlockExpression
    val nullAssignExpr = "${if (notNull) "_" else ""}binding = null"
    if (onDestroyView == null) {
        body?.addBefore(
            ktPsiFactory.createFunction(
                """
           override fun onDestroyView() {
               super.onDestroyView()
               $nullAssignExpr
           }
       """.trimIndent()
            ), body?.rBrace
        )
    } else {
        val firstChild = onDestroyView.children.first()
        onDestroyView.addAfter(ktPsiFactory.createExpression(nullAssignExpr), firstChild)
        onDestroyView.addAfter(ktPsiFactory.createNewLine(), firstChild)
    }
}

internal fun migrateToFragment(
    ktClass: KtClass,
    project: Project,
    psiFactory: KtPsiFactory
) {

    val onViewCreated: PsiElement? = ktClass.findOnViewCreated()

    var layoutName = PsiTreeUtil.collectElements(ktClass, PsiElementFilter { it is KtDotQualifiedExpression })
        ?.find { it.text.contains("R.layout") }
        ?.text?.substringAfterLast(".")?.substringBefore(",")

    val elements = getSyntheticElements(ktClass)

    if (elements.isEmpty()) {
        return
    }

    val mainLayoutFile: PsiFile? = getLayoutFiles(
        resolvePropertyDescriptor(elements.first() as KtNameReferenceExpression)!!,
        getLayoutManager(elements.first())!!
    ).first()

    //TODO show dialog to select one of files
    if (mainLayoutFile == null || layoutName == null) {
        layoutName = mainLayoutFile?.virtualFile?.nameWithoutExtension
    }

//    val includedLayoutsWithElements =
//        elements.filter { it.second.find { it.virtualFile.nameWithoutExtension != layoutName } != null }
//
//    val includedElements = mutableListOf<Triple<PsiElement, String, String>>()
//
//    val visitor = AndroidXmlLayoutVisitor(includedLayoutsWithElements) { name, id, tag, element ->
//        if (id.isNullOrBlank()) {
//            tag.add(
//                XmlElementFactory.getInstance(project)
//                    .createXmlAttribute("android:id", "@+id/${name.plus("_view")}")
//            )
//        }
//        includedElements += Triple(element.first, name.toCamelCaseAsVar(), id ?: name.plus("_view"))
//    }
//    mainLayoutFile?.accept(visitor)

    val notNull = true

    ktClass.project.executeWriteCommand("Migrating to viewbinding") {

        ktClass.removeBindingInsideOnDestroyView(psiFactory, notNull)

        mainLayoutFile?.virtualFile?.nameWithoutExtension?.let { name ->
            ktClass.addBindingProperty(psiFactory, name, notNull)
            (onViewCreated as? KtFunction)?.insertBindingInitializer(
                psiFactory,
                name,
                fragment = true,
                notNull = notNull
            )
        }

//        includedElements.forEach {
//            it.first.replace(psiFactory.createExpression("binding?.${it.third.toCamelCaseAsVar()}.${it.first.text.toCamelCaseAsVar()}"))
//        }

//        val includedElementsPsi = includedElements.map { it.first }

//        convertToViewBindingWithNullableProperty(elements, psiFactory)
        if (notNull) {
            convertToViewBindingWithNotNullGetter(elements, psiFactory)
        } else {
            convertToViewBindingWithNullableProperty(elements, psiFactory)
        }

        ktClass.removeSyntheticImports()

        ktClass.containingKtFile.commitAndUnblockDocument()
        ShortenReferences.DEFAULT.process(ktClass)
        CodeStyleManager.getInstance(project).apply {
            reformat(ktClass.containingKtFile)
        }
    }
}

private fun convertToViewBindingWithNullableProperty(
    elements: List<PsiElement>,
    psiFactory: KtPsiFactory
) {
    elements.forEach {
        if (it.isValid) {
            val ktDotQualifiedExpression = it.parent as? KtDotQualifiedExpression
            if (ktDotQualifiedExpression != null) {
                val replace = psiFactory.createExpression("binding?.${it.text.toCamelCaseAsVar()}")
                val replacedEl = replaceWithSafeCall(ktDotQualifiedExpression, replace)
                replacedEl?.let {
                    convertToViewBindingWithNullableProperty(getSyntheticElements(replacedEl), psiFactory)
                }
            } else {
                val replace = KtPsiFactory(it).createExpression("binding?.${it.text.toCamelCaseAsVar()}")
                it.replace(replace)
            }
        }
    }
}

private fun convertToViewBindingWithNotNullGetter(
    elements: List<PsiElement>,
    psiFactory: KtPsiFactory
) {
    elements.forEach {
        if (it.isValid) {
            val replace = psiFactory.createExpression("binding.${it.text.toCamelCaseAsVar()}")
            it.replace(replace)
        }
    }
}

//private fun convertChilds(
//    ktDotQualifiedExpression: KtDotQualifiedExpression?,
//    elements: List<PsiElement>,
//    psiFactory: KtPsiFactory
//) {
//    PsiTreeUtil.collectElements(ktDotQualifiedExpression, PsiElementFilter { it is KtNameReferenceExpression })
//        .filter { elements.contains(it) && it.parent != ktDotQualifiedExpression }.forEach {
//            val exprD = it.parent as? KtDotQualifiedExpression
//            if (exprD != null) {
//                val rp = psiFactory.createExpression("binding?.${it.text.toCamelCaseAsVar()}")
//                replaceWithSafeCall(exprD, rp)
//            } else {
//                val rp = KtPsiFactory(it).createExpression("binding?.${it.text.toCamelCaseAsVar()}")
//                it.replace(rp)
//            }
//        }
//}

