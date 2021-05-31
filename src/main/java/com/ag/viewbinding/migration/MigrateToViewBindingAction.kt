package com.ag.viewbinding.migration

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiElementFilter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import org.jetbrains.kotlin.android.synthetic.AndroidConst
import org.jetbrains.kotlin.idea.actions.generate.KotlinGenerateActionBase
import org.jetbrains.kotlin.idea.caches.project.getModuleInfo
import org.jetbrains.kotlin.idea.util.application.executeWriteCommand
import org.jetbrains.kotlin.psi.*
import org.jetbrains.plugins.groovy.lang.psi.util.childrenOfType

private enum class MigrationType(val type: String) {
    FRAGMENT("androidx.fragment.app.Fragment"),
    ACTIVITY("android.app.Activity")
}

class MigrateToViewBindingAction : KotlinGenerateActionBase() {

    override fun startInWriteAction(): Boolean = false

    override fun isValidForFile(project: Project, editor: Editor, file: PsiFile): Boolean {
        return file is KtFile && file.classes.any {
            InheritanceUtil.getSuperClasses(it)
                .any { clazz -> MigrationType.values().any { it.type == (clazz.qualifiedName) } }
        }
    }

    override fun isValidForClass(targetClass: KtClassOrObject): Boolean {
        return targetClass.containingKtFile.importDirectives.isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {

        val ktClass = (file as KtFile).classes[0].navigationElement as KtClass
        val psiFactory = KtPsiFactory(project)

        when (MigrationType.values().find {
            InheritanceUtil.getSuperClasses(file.classes[0]).any { clazz -> it.type == clazz.qualifiedName }
        }) {
            MigrationType.FRAGMENT -> migrateToFragment(ktClass, project, psiFactory)
            MigrationType.ACTIVITY -> migrateActivity(ktClass, project, psiFactory)
        }
    }

    private fun migrateActivity(
        ktClass: KtClass,
        project: Project,
        psiFactory: KtPsiFactory
    ) {
        var onCreateMethod = PsiTreeUtil.collectElements(ktClass, PsiElementFilter { it is KtCallExpression })
            .find { it.text.contains("setContentView") || it.text.contains("onCreateView") }

        val layoutName = onCreateMethod?.childrenOfType<KtValueArgumentList>()?.flatMap { it.arguments }
            ?.find { it.text.contains("R.layout") }?.text?.substringAfterLast(".")

        val elements =
            PsiTreeUtil.collectElements(ktClass, PsiElementFilter { it is KtNameReferenceExpression }).filter {
                (it as KtNameReferenceExpression).getReferencedNameElement().getModuleInfo().findAndroidModuleInfo()

                val layoutManager = getLayoutManager(it) ?: return@filter false
                val propertyDescriptor = resolvePropertyDescriptor(it) ?: return@filter false

                val psiElements = layoutManager.propertyToXmlAttributes(propertyDescriptor)
                val valueElements = psiElements.mapNotNull { (it as? XmlAttribute)?.valueElement as? PsiElement }
                return@filter valueElements.isNotEmpty()
            }.map {
                it to getLayoutFiles(
                    resolvePropertyDescriptor(it as KtNameReferenceExpression)!!,
                    getLayoutManager(it)!!
                )
            }

        val mainLayoutFile = elements.flatMap { it.second }.find { it.virtualFile.nameWithoutExtension == layoutName }
        val includedLayoutsWithElements =
            elements.filter { it.second.find { it.virtualFile.nameWithoutExtension != layoutName } != null }

        val includedElements = mutableListOf<Triple<PsiElement, String, String>>()

        val visitor = AndroidXmlLayoutVisitor(includedLayoutsWithElements) { name, id, tag, element ->
            if (id.isNullOrBlank()) {
                tag.add(
                    XmlElementFactory.getInstance(project)
                        .createXmlAttribute("android:id", "@+id/${name.plus("_view")}")
                )
            }
            includedElements += Triple(element.first, name.toCamelCaseAsVar(), id ?: name.plus("_view"))
        }
        mainLayoutFile?.accept(visitor)

        ktClass.project.executeWriteCommand("Migrating to viewbinding") {
            //            ktClass.containingKtFile.importDirectives.last().delete()
            //            ktClass.body
            //                ?.addAfter(psiFactory.createProperty("private lateinit var binding: ActivityMainBinding"), ktClass.body?.properties?.last())
            //            ktClass
            //                .addBefore(psiFactory.createImportDirective(ImportPath.fromString("com.example.plugin_testing_sample.databinding.ActivityMainBinding")), ktClass.containingKtFile.importDirectives.last())

            includedElements.forEach {
                it.first.replace(psiFactory.createExpression("binding.${it.third.toCamelCaseAsVar()}.${it.first.text.toCamelCaseAsVar()}"))
            }

            val includedElementsPsi = includedElements.map { it.first }

            elements.filter { !includedElementsPsi.contains(it.first) }.forEach {
                it.first.replace(psiFactory.createExpression("binding.${it.first.text.toCamelCaseAsVar()}"))
            }

            val replaceExpr = psiFactory.createExpression("setContentView(binding.root)")
            val newLineElement = psiFactory.createNewLine()

            onCreateMethod = onCreateMethod?.replace(replaceExpr)
            val binddingExpr = psiFactory.createExpression("binding = ActivityMainBinding.inflate(layoutInflater)")
            onCreateMethod?.parent?.addBefore(
                binddingExpr,
                onCreateMethod
            )
            onCreateMethod?.parent?.addBefore(
                newLineElement,
                onCreateMethod
            )
            //binddingExpr.add(psiFactory.createNewLine())
        }
    }

    private fun isLayoutPackageIdentifier(reference: KtNameReferenceExpression): Boolean {
        val probablyVariant = reference.parent as? KtDotQualifiedExpression ?: return false
        val probablyKAS = probablyVariant.receiverExpression as? KtDotQualifiedExpression ?: return false
        return probablyKAS.receiverExpression.text == AndroidConst.SYNTHETIC_PACKAGE
    }

}