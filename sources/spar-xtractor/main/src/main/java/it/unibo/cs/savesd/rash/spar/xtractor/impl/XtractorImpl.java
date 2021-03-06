package it.unibo.cs.savesd.rash.spar.xtractor.impl;

import it.unibo.cs.savesd.rash.spar.xtractor.Xtractor;
import it.unibo.cs.savesd.rash.spar.xtractor.config.ConfigProperties;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.Abstract;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.BodyMatter;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.DoCOIndividualBuilder;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.DoCOIndividuals;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.Expression;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.InvalidDoCOIndividualException;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.MissmatchingDoCOClassDeclarationException;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.NotInstantiableIndividualException;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.Paragraph;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.Section;
import it.unibo.cs.savesd.rash.spar.xtractor.doco.Sentence;
import it.unibo.cs.savesd.rash.spar.xtractor.vocabularies.DoCOClass;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import opennlp.tools.sentdetect.SentenceDetector;
import opennlp.tools.sentdetect.SentenceDetectorME;
import opennlp.tools.sentdetect.SentenceModel;
import opennlp.tools.util.InvalidFormatException;

import org.apache.commons.configuration.Configuration;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.Syntax;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.sparql.expr.NodeValue;
import com.hp.hpl.jena.sparql.function.FunctionBase2;
import com.hp.hpl.jena.sparql.function.FunctionRegistry;
import com.hp.hpl.jena.util.FileManager;
import com.hp.hpl.jena.vocabulary.RDFS;

/**
 * 
 * Basic implementation of the interface {@link XtractorTest}.
 * 
 * @author Andrea Nuzzolese
 *
 */

public class XtractorImpl implements Xtractor {

    private Logger log = LoggerFactory.getLogger(XtractorImpl.class);
    private Configuration configuration;
    
    private final String _SENTENCE_DETECTOR_MODEL_EN_DEFAULT_ = "META-INF/models/en-sent.bin";
    private SentenceDetector sentenceDetector;
    private RDFaInjectorImpl rdFaInjector;
    
    /*
     * Namespace
     */
    protected String namespace;
    
    /*
     * HTML elements that can be associated to DoCO objects.
     */
    protected String expressionElement;
    protected String bodyMatterElement;
    protected String sectionElement;
    protected String paragraphElement;
    
    /*
     * Naming conventions
     */
    protected String namingExpression;
    protected String namingBodyMatter;
    protected String namingSection;
    protected String namingParagraph;
    protected String namingSentence;
    
    /*
     * Namespaces and prefixes
     */
    private Map<String,String> prefixes;
    
    public XtractorImpl(Configuration configuration) {
        this.configuration = configuration;
        rdFaInjector = new RDFaInjectorImpl();
        activate();
    }
    
    protected void activate(){
        expressionElement = configuration.getString(ConfigProperties.HTML_ELEMENT_EXPRESSION);
        bodyMatterElement = configuration.getString(ConfigProperties.HTML_ELEMENT_BODY_MATTER);
        sectionElement = configuration.getString(ConfigProperties.HTML_ELEMENT_SECTION);
        paragraphElement = configuration.getString(ConfigProperties.HTML_ELEMENT_PARAGRAPH);
        
        /*
         * Se-up the namespace.
         */
        namespace = configuration.getString(ConfigProperties.NAMESPACE);
        
        /*
         * Set-up the naming conventions.
         */
        namingExpression = configuration.getString(ConfigProperties.NAMING_EXPRESSION);
        namingBodyMatter = configuration.getString(ConfigProperties.NAMING_BODY_MATTER);
        namingSection = configuration.getString(ConfigProperties.NAMING_SECTION);
        namingParagraph = configuration.getString(ConfigProperties.NAMING_PARAGRAPH);
        namingSentence = configuration.getString(ConfigProperties.NAMING_SENTENCE);
        
        /*
         * Set-up the model for sentence detector based on Apache OpenNlp 
         */
        SentenceModel model = null;
        InputStream modelIn = null;
        String sentenceDetectorModelEn = configuration.getString(ConfigProperties.SENTENCE_DETECTOR_MODEL_EN);
        if(sentenceDetectorModelEn == null || sentenceDetectorModelEn.isEmpty()){
            modelIn = getClass().getClassLoader().getResourceAsStream(_SENTENCE_DETECTOR_MODEL_EN_DEFAULT_);
        }
        else{
            try {
                modelIn = new FileInputStream(sentenceDetectorModelEn);
            } catch (FileNotFoundException e) {
                log.error("An error occurred while loading the sentence model.", e);
            }
        }
        
        if(modelIn != null){
            try {
                model = new SentenceModel(modelIn);
            } catch (InvalidFormatException e) {
                log.error("The model for sentence detection provided is in an invalid format.", e);
            } catch (IOException e) {
                log.error("An IO Exception occurred while loading the model for sentence detection.", e);
            }  
            if(model != null) sentenceDetector = new SentenceDetectorME(model);
        }
        
        String namespace = configuration.getString(ConfigProperties.NAMESPACE);
        if(namespace == null)configuration.setProperty(ConfigProperties.NAMESPACE, "");
         
    }
    
    @Override
    public Document extract(String path, DoCOClass level) throws MalformedURLException {
        Document doc = null;
        if(path != null && !path.isEmpty()){
            if(path.startsWith("http://")){
                URL url = new URL(path);
                try {
                    doc = Jsoup.parse(url, 10000);
                } catch (IOException e) {
                    log.error("An error occurred while parsing the document from a remote endpoint.", e);
                }
            }
            else {
                File file = new File(path);
                try {
                    doc = Jsoup.parse(file, null);
                } catch (IOException e) {
                    log.error("An error occurred while parsing the document from local file system.", e);
                }
            }
        }
        
        if(doc != null){
            
            try {
                switch (level) {
                    case Expression:
                        createExpression(doc);
                        break;
                    case BodyMatter:
                        createBodyMatter(doc);
                        break;
                    case Section:
                        createSections(doc);
                        break;
                    case Paragraph:
                        createParagraphs(doc);
                        break;
                    case Sentence:
                        createSentences(doc);
                        break;
                    default:
                        createSentences(doc);
                        break;
                }
            } catch (InvalidDoCOIndividualException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (MissmatchingDoCOClassDeclarationException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (NotInstantiableIndividualException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
                
            return doc;
        }
        else return null;
    }
    
    @Override
    public Map<String,String> getPrefixes(Document document){
        Map<String,String> prefixes = new HashMap<String,String>();
        
        Elements test = document.getElementsByTag("html");
        if(test != null && test.size() > 0){
            Element html = test.first();
            String prefixString = html.attr("prefix");
            if(prefixString != null && !prefixString.trim().isEmpty()){
                String regex = "((.)+:) ((.)+:\\/\\/(.)+)";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(prefixString);
                while(matcher.find()){
                    String prefix = matcher.group(1);
                    String namespace  = matcher.group(3);
                    
                    if(prefix != null)
                        prefix = prefix.trim();
                    if(namespace != null)
                        namespace = namespace.trim();
                    
                    prefixes.put(prefix, namespace);
                }
            }
            
        }
        
        
        return prefixes;
    }
    
    @Override
    public Expression createExpression(Document document) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException{
        /*
         * Add the fabio:Expression
         */
        Elements test = document.getElementsByTag(expressionElement);
        if(test != null){
            Element html = test.first();
            Resource expressionResource = ModelFactory.createDefaultModel().createResource(namingExpression);
            rdFaInjector.createDoCOElement(html, expressionResource, DoCOClass.Expression);
            
            if(this.prefixes == null){
                this.prefixes = getPrefixes(document);
            }
            
            return DoCOIndividualBuilder.build(Expression.class, html, prefixes);
        }
        else throw new InvalidDoCOIndividualException();
        
        
    }
    
    public BodyMatter createBodyMatter(Document document) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException {
        
        /*
         * Add the expression.
         */
        
        Expression expression = createExpression(document);
        
        if(expression != null) return createBodyMatter(expression);
        else return null;
        
        
    }
    
    public BodyMatter createBodyMatter(Expression expression) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException {
        
        Element body = expression.asElement().select(" > body").first();
        
        /*
         * Add the doco:BodyMatter
         */
        Model model = ModelFactory.createDefaultModel();
        Property contains = model.createProperty("http://www.essepuntato.it/2008/12/pattern#contains");
        if(body.nodeName().equals(bodyMatterElement)){
            Resource bodyResource = null;
            String bodyID = body.id();
            if(bodyID != null && !bodyID.isEmpty())
                bodyResource = model.createResource(bodyID);
            else{
                bodyResource = model.createResource(namingBodyMatter);
                body.attr("id", namingBodyMatter);
            }
            rdFaInjector.createDoCOElement(body, contains, bodyResource, DoCOClass.BodyMatter);
        }
        
        if(this.prefixes == null){
            this.prefixes = getPrefixes(body.ownerDocument());
        }
        return DoCOIndividualBuilder.build(BodyMatter.class, body, prefixes);
        
    }
    
    @Override
    public DoCOIndividuals<Section> createSections(Document document) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException {
        
        /*
         * Call createBodyMatter, which in turn calls createExpression. 
         */
        BodyMatter bodyMatter = createBodyMatter(document);
        
        if(bodyMatter != null) return createSections(bodyMatter);
        else return null;
        
    }
    
    public DoCOIndividuals<Section> createSections(BodyMatter bodyMatter) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException {
    
        
        Element body = bodyMatter.asElement();
        
        DoCOIndividuals<Section> sectionIndividuals = new DoCOIndividuals<Section>();
        
        if(this.prefixes == null){
            this.prefixes = getPrefixes(body.ownerDocument());
        }
        
        /*
         * Iterate body's children in order to detect divs that represent sections.
         */
        System.out.println(" > " + sectionElement + ":not([role]), " + sectionElement + "[role*=doc-abstract]");
        Elements sectionElements = body.select(" > " + sectionElement + ":not([role]), " + sectionElement + "[role*=doc-abstract]");
        
        
        Model model = ModelFactory.createDefaultModel();
        Property contains = model.createProperty("http://www.essepuntato.it/2008/12/pattern#contains");
        int sections = 0;
        for(Element section : sectionElements){
            
            String sectionID = section.id();
            Resource sectionResource = null;
            if(sectionID != null && !sectionID.isEmpty()) sectionResource = model.createResource(sectionID);
            else{
                sectionID = namingSection + "-" + sections;
                sectionResource = model.createResource(sectionID);
                section.attr("id", sectionID);
            }
            
            DoCOClass docoClass;
            Class<? extends Section> docoIndividualClass;
            if(section.select("[role*=doc-abstract]").size() > 0){
                docoClass = DoCOClass.Abstract;
                docoIndividualClass  = Abstract.class;
            }
            else{
                docoClass = DoCOClass.Section;
                docoIndividualClass  = Section.class;
            }
            
            rdFaInjector.createDoCOElement(section, contains, sectionResource, docoClass);
            Section sec = DoCOIndividualBuilder.build(docoIndividualClass, section, prefixes);
            sectionIndividuals.add(sec);
            
            /*
             * Add doco:SectionTitle individuals.
             */
            Elements titles = section.select(" > h1");
            if(titles != null){
                Element title = titles.first();
                String titleId = title.id();
                
                
                Resource sectionTitleResource = null;
                if(titleId != null && !titleId.isEmpty()) sectionTitleResource = model.createResource(titleId);
                else{
                    titleId = sectionID + "/title";
                    sectionTitleResource = model.createResource(titleId);
                    title.attr("id", titleId);
                }
                
                /*
                 * Insert title content by means of c4o:hasContent
                 */
                String titleContent = title.html();
                title.html("");
                Element span = title.appendElement("span");
                span.html(titleContent);
                span.attr("property", "http://purl.org/spar/c4o/hasContent");
                
                rdFaInjector.createDoCOElement(title, contains, sectionTitleResource, DoCOClass.SectionTitle);
                
            }
            
            sectionIndividuals.addAll(createSections(sec));
            
        }
        
        return sectionIndividuals; 
        
    }
    
    
    private DoCOIndividuals<Section> createSections(Section section) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException {
    
        
        Element sectionElement = section.asElement();
        
        DoCOIndividuals<Section> sectionIndividuals = new DoCOIndividuals<Section>();
        
        if(this.prefixes == null){
            this.prefixes = getPrefixes(sectionElement.ownerDocument());
        }
        
        /*
         * Iterate body's children in order to detect divs that represent sections.
         */
        System.out.println(" > " + sectionElement + ":not([role])");
        Elements sectionElements = sectionElement.select(" > " + sectionElement.nodeName() + ":not([role])");
        
        Model model = ModelFactory.createDefaultModel();
        Property contains = model.createProperty("http://www.essepuntato.it/2008/12/pattern#contains");
        int sections = 0;
        for(Element subSection : sectionElements){
            
            String sectionID = subSection.id();
            Resource sectionResource = null;
            if(sectionID != null && !sectionID.isEmpty()) sectionResource = model.createResource(sectionID);
            else{
                sectionID = sectionElement.attr("resource") + "/" + sections;
                sectionResource = model.createResource(sectionID);
                subSection.attr("id", sectionID);
            }
            
            rdFaInjector.createDoCOElement(subSection, contains, sectionResource, DoCOClass.Section);
            Section subSec = DoCOIndividualBuilder.build(Section.class, subSection, prefixes);
            sectionIndividuals.add(subSec);
            
            /*
             * Add doco:SectionTitle individuals.
             */
            Elements titles = subSection.select(" > h1");
            if(titles != null){
                Element title = titles.first();
                String titleId = title.id();
                
                
                Resource sectionTitleResource = null;
                if(titleId != null && !titleId.isEmpty()) sectionTitleResource = model.createResource(titleId);
                else{
                    titleId = sectionID + "/title";
                    sectionTitleResource = model.createResource(titleId);
                    title.attr("id", titleId);
                }
                
                /*
                 * Insert title content by means of c4o:hasContent
                 */
                String titleContent = title.html();
                title.html("");
                Element span = title.appendElement("span");
                span.html(titleContent);
                span.attr("property", "http://purl.org/spar/c4o/hasContent");
                
                rdFaInjector.createDoCOElement(title, contains, sectionTitleResource, DoCOClass.SectionTitle);
                
            }
            
            
            sectionIndividuals.addAll(createSections(subSec));
        }
        
        return sectionIndividuals; 
        
    }

    @Override
    public DoCOIndividuals<Paragraph> createParagraphs(Document document) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException {
        
        /*
         * Call createSections, that calls createBodyMatter, which in turn calls createExpression. 
         */
        DoCOIndividuals<Section> sections = createSections(document);
        if(sections != null){
            DoCOIndividuals<Paragraph> paragraphs = new DoCOIndividuals<Paragraph>();
            for(Section section : sections){
                paragraphs.addAll(createParagraphs(section));
            }
            return paragraphs;
        }
        else return null;
    }
    
    @Override
    public DoCOIndividuals<Paragraph> createParagraphs(Section section) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException{
        
        DoCOIndividuals<Paragraph> paragraphIndividuals = new DoCOIndividuals<Paragraph>();
        
        String sectionUri = section.getURI();
        if(sectionUri == null) sectionUri = namingSection;
        
        Model model = ModelFactory.createDefaultModel();
        Property contains = model.createProperty("http://www.essepuntato.it/2008/12/pattern#contains");
        Elements paragraphElements = section.asElement().select(" > " + paragraphElement);
        
        if(this.prefixes == null){
            this.prefixes = getPrefixes(section.asElement().ownerDocument());
        }
                    
        int paragraphs = 0;
        for(Element paragraph : paragraphElements){
            paragraphs += 1;
            
            Resource paragraphResource = null;
            String paragraphID = paragraph.id();
            if(paragraphID != null && !paragraphID.isEmpty())
                paragraphResource = model.createResource(paragraphID);
            else{
                String uri = sectionUri + "/" + namingParagraph + "-" + paragraphs;
                paragraphResource = model.createResource(uri);
                paragraph.attr("id", uri);
            }
            rdFaInjector.createDoCOElement(paragraph, contains, paragraphResource, DoCOClass.Paragraph);
            paragraphIndividuals.add(DoCOIndividualBuilder.build(Paragraph.class, paragraph, prefixes));
        }
        
        return paragraphIndividuals;
        
    }
    
    @Override
    public DoCOIndividuals<Sentence> createSentences(Document document) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException{
        
        /*
         * Call createParagraphs, which recursively calls: 
         *   - createSections; 
         *   - createBodyMatter;
         *   - createExpression. 
         */
        DoCOIndividuals<Paragraph> paragraphs = createParagraphs(document);
        if(paragraphs != null){
            DoCOIndividuals<Sentence> sentences = new DoCOIndividuals<Sentence>();
            for(Paragraph paragraph : paragraphs){
                sentences.addAll(createSentences(paragraph));
            }
            return sentences;
        }
        else return null;
        
    }
    
    @Override
    public DoCOIndividuals<Sentence> createSentences(Paragraph paragraph) throws InvalidDoCOIndividualException, MissmatchingDoCOClassDeclarationException, NotInstantiableIndividualException {
        
        DoCOIndividuals<Sentence> sentenceIndividuals = new DoCOIndividuals<Sentence>();
        
        String paragraphUri = paragraph.getURI();
        if(paragraphUri == null) paragraphUri = namespace + namingSection + "/" + namingParagraph;
        
        Model model = ModelFactory.createDefaultModel();
        
        Property contains = model.createProperty("http://www.essepuntato.it/2008/12/pattern#contains");
        Property hasContent = model.createProperty("http://purl.org/spar/c4o/hasContent");
        
        if(this.prefixes == null){
            this.prefixes = getPrefixes(paragraph.asElement().ownerDocument());
        }
        
        Element paragraphElement = paragraph.asElement();
        String text = paragraphElement.html();
        paragraphElement.html("");
        String[] sentences = detectSentences(text);
        for(int i=0; i<sentences.length; i++){
            String uri = paragraphUri + "/" + namingSentence + "-" + (i+1);
            Resource sentenceResource = model.createResource(uri);
            
            Element span = rdFaInjector.appendDoCOElement(paragraphElement, Tag.valueOf("span"), contains, sentenceResource, DoCOClass.Sentence);
            Element contentSpan = rdFaInjector.appendDoCOElement(span, Tag.valueOf("span"), hasContent);
            
            contentSpan.html(sentences[i]);
            
            sentenceIndividuals.add(DoCOIndividualBuilder.build(Sentence.class, span, prefixes));
            
        }
        
        return sentenceIndividuals;
        
    }
    
    private String[] detectSentences(String text){
        return sentenceDetector.sentDetect(text);
    }
    
    public static void main(String[] args) {
        
        FunctionRegistry.get().put("http://seat.pg/andrea/func/normalizeLabel", RefactorLabel.class);
        FunctionRegistry.get().put("http://seat.pg/andrea/func/setLang", Language.class);
        try {
            Model model = FileManager.get().loadModel("/Users/andrea/Documents/software/gmde/Andrea_Stanbol/dizionario-seat.rdf");
            
            
            String sparql = "PREFIX dbpedia-owl: <http://dbpedia.org/ontology/> " +
            		        "PREFIX skos: <http://www.w3.org/2004/02/skos/core#> " +
            		        "PREFIX muto: <http://purl.org/muto/core#> " +
            		        "PREFIX rdfs: <" + RDFS.getURI() + "> " +
            		        "PREFIX func: <http://seat.pg/andrea/func/> " +
            		        "CONSTRUCT{" +
            		        "?place a dbpedia-owl:Place . " +
            		        "?place rdfs:label ?label . " +
            		        "?place rdfs:label ?labelCapital . " +
            		        "?place rdfs:label ?labelNoStopwords " +
            		        /*"?key a muto:Tag . ?key rdfs:label ?label . " +*/ 
            		        /*"?cat a skos:Concept . ?cat rdfs:label ?label . ?cat rdfs:label ?labelCapital" +*/
            		        "} " +
            		        "WHERE{ " +
            		        "?place rdfs:label ?placeLabel . BIND(func:setLang(func:normalizeLabel(?placeLabel, true), \"it\") AS ?label) . " +
            		        "BIND(func:setLang(?placeLabel, \"it\") AS ?labelCapital) . " +
            		        "BIND(func:setLang(func:normalizeLabel(?placeLabel, false), \"it\") AS ?labelNoStopwords) . " +
            		        "FILTER(REGEX(STR(?place), \"^http://seat.pg/loc/\")) . " +
            		        /*"?key rdfs:label ?keyLabel . BIND(func:setLang(?keyLabel, \"it\") as ?label) " +*/
                            /*"FILTER(REGEX(STR(?key), \"^http://seat.pg/key/\")) . " +*/
                            /*"?cat rdfs:label ?catLabel . BIND(func:setLang(?catLabel, \"it\") as ?label) " + */
                            /*"FILTER(REGEX(STR(?cat), \"^http://seat.pg/cat/\")) " +*/
            		        "}";
            Query query = QueryFactory.create(sparql, Syntax.syntaxARQ);
            QueryExecution queryExecution = QueryExecutionFactory.create(query, model);
            model = queryExecution.execConstruct();
            
            OutputStream out = new FileOutputStream("/Users/andrea/Documents/software/stanbol/entityhub/indexing/genericrdf/target/indexing/resources/rdfdata/dizionario-seat-classified.rdf");
            model.write(out);
            out.flush();
            out.close();
            
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    
    public static class RefactorLabel extends FunctionBase2 {
        
        private Set<String> stopwords;
        public RefactorLabel() {
            try {
                stopwords = readFile(new File("it.stopword"));
            } catch (IOException e) {
                e.printStackTrace();
                stopwords = new HashSet<String>();
            }
            
            
        }
        
        private Set<String> readFile(File stopwordFile) throws IOException
        {
            BufferedReader reader = null;
            try {
                HashSet<String> words = new HashSet<String>();
                reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(stopwordFile), Charset.forName("UTF-8")));
                String line = null;
                while((line=reader.readLine()) != null)
                {
                    int i = line.indexOf('|');
                    if (i >= 0)
                        line = line.substring(0, i);
                    line = line.trim();
                    if (line.length() > 0){
                        words.add(line);
                    }
                }
                return words;
            } finally {
                try {reader.close();}
                catch(Exception e){}
            }
        }

        @Override
        public NodeValue exec(NodeValue literalValue, NodeValue useStopWords) {
            System.out.println("L: " + literalValue.isLiteral());
            System.out.println("B: " + useStopWords.isBoolean());
            if(literalValue.isLiteral() && useStopWords.isBoolean()){
                
                String literal = literalValue.asNode().getLiteralLexicalForm();
                String[] literalParts = literal.split(" ");
                literal = null;
                
                System.out.println(literal);
                for(String literalPart : literalParts){
                    
                    
                    boolean stopword = false;
                    if(literal != null && stopwords.contains(literalPart.trim().toLowerCase())) stopword = true;
                    
                    if(literal == null) literal = "";
                    else literal += " ";
                    
                    if(stopword && useStopWords.getBoolean()) literal += literalPart.toLowerCase();
                    else{
                        char[] literalPartChars = literalPart.toCharArray();
                        literal += Character.toUpperCase(literalPartChars[0]) + String.copyValueOf(literalPartChars, 1, literalPartChars.length-1).toLowerCase();  
                    }
                }
                
                return NodeValue.makeString(literal);
            }
            return null;
        }
        
    }
    
    public static class Language extends FunctionBase2 {

        @Override
        public NodeValue exec(NodeValue literal, NodeValue langTag) {
            if(literal.isLiteral() && langTag.isLiteral()){
                return NodeValue.makeNode(literal.asNode().getLiteralLexicalForm(), langTag.getString(), (Node)null);   
            }
            return null;
        }
        
        
        
    }
}
