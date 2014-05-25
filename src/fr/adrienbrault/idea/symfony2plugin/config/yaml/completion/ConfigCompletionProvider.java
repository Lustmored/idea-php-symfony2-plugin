package fr.adrienbrault.idea.symfony2plugin.config.yaml.completion;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionProvider;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.util.ProcessingContext;
import fr.adrienbrault.idea.symfony2plugin.Symfony2Icons;
import fr.adrienbrault.idea.symfony2plugin.util.yaml.YamlHelper;
import org.apache.commons.lang.StringUtils;
import org.apache.xerces.dom.CommentImpl;
import org.apache.xerces.dom.DeferredTextImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.YAMLCompoundValue;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.w3c.dom.*;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ConfigCompletionProvider extends CompletionProvider<CompletionParameters> {

    @Override
    protected void addCompletions(@NotNull CompletionParameters completionParameters, ProcessingContext processingContext, @NotNull CompletionResultSet completionResultSet) {

        PsiElement element = completionParameters.getOriginalPosition();
        if(element == null) {
            return;
        }

        PsiElement yamlCompount = element.getParent();
        if(!(yamlCompount instanceof YAMLCompoundValue || yamlCompount instanceof YAMLKeyValue)) {
            return;
        }

        // get all parent yaml keys
        List<String> items = YamlHelper.getParentArrayKeys(element);
        if(items.size() == 0) {
            return;
        }

        // reverse to get top most item first
        Collections.reverse(items);

        Document document;
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();

            VirtualFile virtualFile = VfsUtil.findRelativeFile(element.getProject().getBaseDir(), ".idea", "symfony2-config.xml");
            if(virtualFile != null) {
                document = builder.parse(VfsUtil.virtualToIoFile(virtualFile));
            } else {
                document = builder.parse(ConfigCompletionProvider.class.getResourceAsStream("/resources/symfony2-config.xml"));
            }

        } catch (ParserConfigurationException e) {
            return;
        } catch (SAXException e) {
            return;
        } catch (IOException e) {
            return;
        }


        Node configNode = getMatchingConfigNode(document, items);
        if(configNode == null) {
            return;
        }


        // get config on node attributes
        NamedNodeMap attributes = configNode.getAttributes();
        if(attributes.getLength() > 0) {
            Map<String, String> nodeDocVars = getNodeCommentVars(configNode);
            for (int i = 0; i < attributes.getLength(); i++) {
                completionResultSet.addElement(getNodeAttributeLookupElement(attributes.item(i), nodeDocVars));
            }
        }


        // check for additional child node
        if(configNode instanceof Element) {

            NodeList nodeList1 = ((Element) configNode).getElementsByTagName("*");
            for (int i = 0; i < nodeList1.getLength(); i++) {
                LookupElementBuilder nodeTagLookupElement = getNodeTagLookupElement(nodeList1.item(i));
                if(nodeTagLookupElement != null) {
                    completionResultSet.addElement(nodeTagLookupElement);
                }
            }

        }


    }

    private LookupElementBuilder getNodeAttributeLookupElement(Node node, Map<String, String> nodeVars) {

        String nodeName = node.getNodeName();
        LookupElementBuilder lookupElementBuilder = LookupElementBuilder.create(nodeName).withIcon(Symfony2Icons.CONFIG_VALUE);

        String textContent = node.getTextContent();
        if(StringUtils.isNotBlank(textContent)) {
            lookupElementBuilder = lookupElementBuilder.withTailText("(" + textContent + ")", true);
        }
        
        if(nodeVars.containsKey(nodeName)) {
            lookupElementBuilder = lookupElementBuilder.withTypeText(StringUtil.shortenTextWithEllipsis(nodeVars.get(nodeName), 100, 0), true);
        }

        return lookupElementBuilder;
    }

    @Nullable
    private LookupElementBuilder getNodeTagLookupElement(Node node) {

        String nodeName = node.getNodeName();
        boolean prototype = isPrototype(node);

        // prototype "connection" must be "connections" so pluralize
        if(prototype) {
            nodeName = StringUtil.pluralize(nodeName);
        }

        LookupElementBuilder lookupElementBuilder = LookupElementBuilder.create(nodeName).withIcon(Symfony2Icons.CONFIG_PROTOTYPE);

        if(prototype) {
            lookupElementBuilder = lookupElementBuilder.withTypeText("Prototype", true);
        }

        return lookupElementBuilder;
    }

    @Nullable
    private Element getElementByTagNameWithUnPluralize(Element element, String tagName) {

        NodeList nodeList = element.getElementsByTagName(tagName);
        if(nodeList.getLength() > 0) {
            return (Element) nodeList.item(0);
        }

        String unpluralize = StringUtil.unpluralize(tagName);
        if(unpluralize == null) {
            return null;
        }

        nodeList = element.getElementsByTagName(unpluralize);
        if(nodeList.getLength() > 0) {
            return (Element) nodeList.item(0);
        }

        return null;

    }

    @Nullable
    private Element getElementByTagName(Document element, String tagName) {
        NodeList nodeList = element.getElementsByTagName(tagName);

        if(nodeList.getLength() > 0) {
            return (Element) nodeList.item(0);
        }

        return null;

    }

    @Nullable
    private Node getMatchingConfigNode(Document document, List<String> items) {

        if(items.size() == 0) {
            return null;
        }

        Element currentNodeItem = getElementByTagName(document, items.get(0));
        for (int i = 1; i < items.size(); i++) {

            currentNodeItem = getElementByTagNameWithUnPluralize(currentNodeItem, items.get(i));
            if(currentNodeItem == null) {
                return null;
            }

            if(isPrototype(currentNodeItem)) {
                i++;
            }

        }

        return currentNodeItem;

    }

    private boolean isPrototype(@Nullable Node node) {
        if(node == null) return false;

        Node previousSibling = node.getPreviousSibling();
        if(previousSibling == null) {
            return false;
        }

        // we can have multiple comments similar to docblock to a node
        // search for prototype
        Node comment = previousSibling.getPreviousSibling();
        while (comment instanceof CommentImpl || comment instanceof DeferredTextImpl) {

            if(comment instanceof CommentImpl) {
                if(comment.getTextContent().toLowerCase().matches("^\\s*prototype.*")) {
                    return true;
                }
            }

            comment = comment.getPreviousSibling();
        }

        return false;
    }

    @NotNull
    private Map<String, String> getNodeCommentVars(@Nullable Node node) {
        Map<String, String> comments = new HashMap<String, String>();
        
        if(node == null) return comments;

        Node previousSibling = node.getPreviousSibling();
        if(previousSibling == comments) {
            return comments;
        }

        // get variable decl: "foo: test"
        Pattern compile = Pattern.compile("^\\s*([\\w_-]+)\\s*:\\s*(.*?)$");

        Node comment = previousSibling.getPreviousSibling();
        while (comment instanceof CommentImpl || comment instanceof DeferredTextImpl) {

            if(comment instanceof CommentImpl) {

                // try to find a var decl
                String trim = StringUtils.trim(comment.getTextContent());
                Matcher matcher = compile.matcher(trim);
                if (matcher.find()) {
                    comments.put(matcher.group(1), matcher.group(2));
                }

            }

            comment = comment.getPreviousSibling();
        }

        return comments;
    }

}
