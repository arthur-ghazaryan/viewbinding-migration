package com.ag.viewbinding.migration

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlTag
import org.jetbrains.kotlin.android.synthetic.AndroidConst
import org.jetbrains.kotlin.android.synthetic.androidIdToName

class AndroidXmlLayoutVisitor(
    val layoutsToFind: List<Pair<PsiElement, List<PsiFile>>>,
    val elementCallback: (String, String?, XmlTag, Pair<PsiElement, List<PsiFile>>) -> Unit
) :
    XmlElementVisitor() {

    override fun visitElement(element: PsiElement) {
        element.acceptChildren(this)
    }

    override fun visitXmlElement(element: XmlElement?) {
        element?.acceptChildren(this)
    }

    override fun visitXmlTag(tag: XmlTag?) {
        val localName = tag?.localName ?: ""
        if (localName != "include") {
            tag?.acceptChildren(this)
            return
        }

        val layoutAttribute = tag?.getAttribute("layout")
        if (layoutAttribute != null) {
            val layoutAttributeValue = layoutAttribute.value
            val idAttribute = tag.getAttribute(AndroidConst.ID_ATTRIBUTE_NO_NAMESPACE, AndroidConst.ANDROID_NAMESPACE)
            if (layoutAttributeValue != null) {
                val name = layoutAttributeValue.substringAfterLast("/")
                val element =
                    layoutsToFind.find { it.second.find { it.virtualFile.nameWithoutExtension == name } != null }
                if (name.isNotBlank() && element != null) elementCallback(
                    name,
                    idAttribute?.value?.let { androidIdToName(it)?.name },
                    tag,
                    element
                )
            }
        }

        tag?.acceptChildren(this)
    }
}
