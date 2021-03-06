package org.fairsharing.owl2neo;

import openllet.owlapi.OpenlletReasonerFactory;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.neo4j.graphdb.*;
import org.neo4j.graphdb.index.UniqueFactory;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.semanticweb.HermiT.ReasonerFactory;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.OWLReasoner;


import org.semanticweb.owlapi.search.EntitySearcher;
import uk.ac.manchester.cs.jfact.JFactFactory;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Owl2Neo4jLoader {

    private static final int OK_STATUS = 0;
    private static final int ERR_STATUS = 1;

    private static final String HASH = "#";
    private static final String GREATER_THAN = ">";
    private static final String IS_A = "isA";
    private static final String PART_OF = "partOf";

    public static final String GRAPH_DB_PATH = "var/fairsharing-ont-lite.db";
    // public static final String GRAPH_DB_PATH = "neo4j-community-3.2.3/data/fairsharing-ont.db";
    private static final String OWL_THING = "owl:Thing";

    public static final String OPENLLET = "OPENLLET";
    public static final String PELLET = "PELLET";
    public static final String HERMIT = "HERMIT";
    public static final String JFACT = "JFACT";

    private static final String OBO_ALTERNATIVE_TERM_IRI = "http://purl.obolibrary.org/obo/IAO_0000118";
    private static final String FAIRSHARING_ALTERNATIVE_TERM = "http://www.fairsharing.org/fairsharing/FAIRO_0000001";

    private static final String OIO_HAS_EXACT_SYNONYM = "http://www.geneontology.org/formats/oboInOwl#hasExactSynonym";
    private static final String OIO_HAS_RELATED_SYNONYM = "http://www.geneontology.org/formats/oboInOwl#hasRelatedSynonym";
    private static final String OIO_HAS_BROAD_SYNONYM = "http://www.geneontology.org/formats/oboInOwl#hasBroadSynonym";

    private GraphDatabaseService graphDb;
    private OWLOntology ontology;
    private OWLDataFactory dataFactory;

    private OWLReasoner reasoner;

    private OWLAnnotationProperty oboAlternativeTerm;
    private List<OWLAnnotationProperty> alternativeTerms;
    private List<OWLAnnotationProperty> synonyms;

    @Inject
    public Owl2Neo4jLoader(GraphDatabaseService graphDb, OWLOntology ontology, OWLDataFactory dataFactory) {
        this.graphDb = graphDb;
        this.ontology = ontology;
        this.dataFactory = dataFactory;
    }

    public GraphDatabaseService getGraphDb() {
        return graphDb;
    }

    public void setGraphDb(GraphDatabaseService graphDb) {
        this.graphDb = graphDb;
    }

    public OWLReasoner getReasoner() {
        return OpenlletReasonerFactory.getInstance().createReasoner(ontology);
    }

    public OWLReasoner getReasoner(String reasonerType) {
        switch (reasonerType) {
            case HERMIT:
                return (new ReasonerFactory()).createReasoner(ontology);

            case JFACT:
                return (new JFactFactory()).createReasoner(ontology);

            default:
                return OpenlletReasonerFactory.getInstance().createReasoner(ontology);
        }

    }

    public OWLOntology getOntology() {
        return ontology;
    }

    public void setOntology(OWLOntology ontology) {
        this.ontology = ontology;
    }

    public OWLAnnotationProperty getOboAlternativeTerm() {
        return oboAlternativeTerm;
    }

    public void setOboAlternativeTerm(OWLAnnotationProperty oboAlternativeTerm) {
        this.oboAlternativeTerm = oboAlternativeTerm;
    }

    public void loadOboAlternativeTermFromOntology() {
        Optional<OWLAnnotationProperty> optional = ontology.annotationPropertiesInSignature()
                .filter((OWLAnnotationProperty ap) -> ap.getIRI().getIRIString().equalsIgnoreCase(OBO_ALTERNATIVE_TERM_IRI)).findFirst();
        if (optional.isPresent()) {
            System.out.println("OBO Alternative Term is: " + optional.get());
            oboAlternativeTerm = optional.get();
            List<OWLAnnotationProperty> subproperties = new ArrayList<OWLAnnotationProperty>(Arrays.asList(
                    EntitySearcher.getSubProperties(oboAlternativeTerm, ontology).toArray(OWLAnnotationProperty[]::new)));
            alternativeTerms = new ArrayList<OWLAnnotationProperty>();
            alternativeTerms.add(oboAlternativeTerm);
            alternativeTerms.addAll(subproperties);
            System.out.println("Alternative Terms are: " + optional.get());

        }
        else {
            System.out.println("OBO Alternative Term not found in Ontology " + ontology);
            oboAlternativeTerm = null;
        }
    }

    public List<OWLAnnotationProperty> loadOWLAnnotationPropertyFromOntologyByIriString(String iriString) {
        List<OWLAnnotationProperty> owlAnnotationProperties = new ArrayList<OWLAnnotationProperty>();
        Optional<OWLAnnotationProperty> optional = ontology.annotationPropertiesInSignature()
                .filter((OWLAnnotationProperty ap) -> ap.getIRI().getIRIString().equalsIgnoreCase(iriString)).findFirst();
        if (optional.isPresent()) {
            owlAnnotationProperties.add(optional.get());
        }
        return owlAnnotationProperties;
    }

    private Node getOrCreateWithUniqueFactory(String nodeName) {

        UniqueFactory<Node> factory = new UniqueFactory.UniqueNodeFactory(graphDb, "index") {
            @Override
            protected void initialize(Node created, Map<String, Object> properties) {
                created.setProperty("className", properties.get("name"));
            }
        };

        return factory.getOrCreate("name", nodeName);
    }

    private void loadAnnotationProperties() throws Exception {
        final OWLReasoner reasoner = getReasoner();
        if (!reasoner.isConsistent()) throw new Exception("Ontology is inconsistent");

        ontology.annotationPropertiesInSignature().forEach((OWLAnnotationProperty ap) -> {
            System.out.println("Annotation property:" + ap);
        });

    }

    private void loadNodes() throws Exception {
        final OWLReasoner reasoner = getReasoner();
        if (!reasoner.isConsistent()) throw new Exception("Ontology is inconsistent");

        Transaction tx = graphDb.beginTx();

        try {
            getOrCreateWithUniqueFactory(OWL_THING);
            ontology.classesInSignature().forEach((OWLClass c) -> {
                String classString = c.toString(), classLabel = classString;
                if (classString.contains(HASH)) {
                    classString = classString.substring(classString.indexOf(HASH)+1, classString.lastIndexOf(GREATER_THAN));
                }
                IRI iri = c.getIRI();
                String iriString = iri.getIRIString();

                Node classNode = getOrCreateWithUniqueFactory(classString);
                classNode.setProperty("iri", iriString);

                EntitySearcher.getAnnotations(c, ontology, dataFactory.getRDFSLabel()).forEach(annotation -> {
                    System.out.println("Annotation: " + annotation);
                    OWLAnnotationProperty property = annotation.getProperty();
                    System.out.println(property.toString());
                    OWLLiteral literal = (OWLLiteral) annotation.getValue();
                    System.out.println(literal.getLiteral());
                    classNode.setProperty("name", literal.getLiteral());
                });
                System.out.println("Current OWL class is: " + classString);

            });
            tx.success();
        }
        finally {
            tx.close();
        }

    }

    private void loadLinks() throws Exception {
        final OWLReasoner reasoner = getReasoner();
        if (!reasoner.isConsistent()) throw new Exception("Ontology is inconsistent");

        Transaction tx = graphDb.beginTx();

        try {
            ontology.classesInSignature().forEach((OWLClass c) -> {
                Node thingNode = getOrCreateWithUniqueFactory(OWL_THING);
                String classString = c.toString(), classLabel = classString;
                if (classString.contains(HASH)) {
                    classString = classString.substring(classString.indexOf(HASH)+1, classString.lastIndexOf(GREATER_THAN));
                }
                Node classNode = getOrCreateWithUniqueFactory(classString);
                NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(c, true);

                if (superClasses.isEmpty()) {
                    classNode.createRelationshipTo(thingNode, RelationshipType.withName(IS_A));
                }
                else {
                    for (org.semanticweb.owlapi.reasoner.Node<OWLClass> parentOWLNode: superClasses) {
                        OWLClassExpression parent = parentOWLNode.getRepresentativeElement();
                        String parentString = parent.toString();
                        if (parentString.contains(HASH)) {
                            parentString = parentString.substring(parentString.indexOf(HASH)+1, parentString.lastIndexOf(GREATER_THAN));
                        }
                        Node parentNode = getOrCreateWithUniqueFactory(parentString);
                        classNode.createRelationshipTo(parentNode, RelationshipType.withName(PART_OF));
                    }
                }
            });
            tx.success();
        }
        finally {
            tx.close();
        }

    }

    public Node getOrCreateOwlThing() {
        /*
        Transaction tx = graphDb.beginTx();
        try {
            tx = graphDb.beginTx(); */
            Node thingNode = getOrCreateWithUniqueFactory(OWL_THING);
            // tx.success();
            return thingNode;
        /*
        }
        finally {
            tx.close();
        }*/
    }

    public void importOntology(String label) throws Exception {
        final OWLReasoner reasoner = getReasoner();
        if (!reasoner.isConsistent()) {
            throw new Exception("Ontology is inconsistent");
        }

        //load declared OWLAnnotationProperties
        ontology.annotationPropertiesInSignature().forEach((OWLAnnotationProperty ap) -> {
            System.out.println("Annotation property:" + ap);
        });

        Transaction tx = graphDb.beginTx();
        try {
            Node thingNode = getOrCreateOwlThing();

            final AtomicInteger counter = new AtomicInteger();
            long totalCount = ontology.classesInSignature().count();
            System.out.println("Total count is: " + totalCount);
            ontology.classesInSignature().forEach(c -> {
                long start = System.nanoTime();
                loadClassAsNode(reasoner, thingNode, c, label);
                long end = System.nanoTime();
                double duration = end - start / 1000000;
                System.out.println("Duration of current iteration is:" + duration + " ms");
                System.out.println("Done item #" + counter.incrementAndGet());
            });
            tx.success();
        }
        catch (Exception e) {
            System.err.println("Owch, shucks, exception thrown:" + e.getMessage());
            e.printStackTrace();
        }
        finally {
            tx.close();
        }

    }

    private void loadClassAsNode(OWLReasoner reasoner, Node thingNode, OWLClass c, String label) {
        String classString = c.toString(), classLabel = classString;
        if (classString.contains(HASH)) {
            classString = classString.substring(classString.indexOf(HASH)+1, classString.indexOf(GREATER_THAN));
        }
        IRI iri = c.getIRI();
        String iriString = iri.getIRIString();

        // Transaction tx = graphDb.beginTx();
        // try {
            Node classNode = getOrCreateWithUniqueFactory(classString);
            classNode.setProperty("iri", iriString);
            classNode.addLabel(Label.label(label));

            EntitySearcher.getAnnotations(c, ontology, dataFactory.getRDFSLabel()).forEach(annotation -> {
                System.out.println("Annotation: " + annotation);
                OWLAnnotationProperty property = annotation.getProperty();
                System.out.println(property.toString());
                OWLLiteral literal = (OWLLiteral) annotation.getValue();
                System.out.println(literal.getLiteral());
                classNode.setProperty("name", literal.getLiteral());
                classNode.setProperty("displayName", literal.getLiteral());
            });

            List<String> alternativeNames = new ArrayList<String>();
            for (OWLAnnotationProperty alternativeTerm : alternativeTerms) {

                EntitySearcher.getAnnotations(c, ontology, alternativeTerm).forEach(annotation -> {
                    System.out.println("Annotation alternative term: " + annotation);
                    OWLLiteral literal = (OWLLiteral) annotation.getValue();
                    String propetyIRIString = alternativeTerm.getIRI().getIRIString();
                    switch (propetyIRIString) {
                        case OBO_ALTERNATIVE_TERM_IRI:
                            alternativeNames.add(literal.getLiteral());
                            break;
                        case FAIRSHARING_ALTERNATIVE_TERM:
                            alternativeNames.add(literal.getLiteral());
                            classNode.setProperty("displayName", literal.getLiteral());
                            break;
                        default:
                            alternativeNames.add(literal.getLiteral());
                    }
                });

            }
            String alternativeNamesString = String.join(",", alternativeNames);
            System.out.println("Alternative terms are: " + alternativeNamesString);
            System.out.println("Display name is: " + classNode.getProperty("displayName", null));

            // classNode.setProperty("alternativeNames", alternativeNamesString);

            classNode.setProperty("alternativeNames", alternativeNames.toArray(new String[alternativeNames.size()]));

            System.out.println("Current OWL class is: " + classString);

            NodeSet<OWLClass> superClasses = reasoner.getSuperClasses(c, true);

            if (superClasses.isEmpty()) {
                classNode.createRelationshipTo(thingNode, RelationshipType.withName(IS_A));
            } else {
                for (org.semanticweb.owlapi.reasoner.Node<OWLClass> parentOWLNode : superClasses) {
                    OWLClassExpression parent = parentOWLNode.getRepresentativeElement();
                    String parentString = parent.toString();
                    if (parentString.contains(HASH)) {
                        parentString = parentString.substring(parentString.indexOf(HASH) + 1, parentString.indexOf(GREATER_THAN));
                    }
                    Node parentNode = getOrCreateWithUniqueFactory(parentString);
                    classNode.createRelationshipTo(parentNode, RelationshipType.withName(PART_OF));
                }
            }
        /*
            tx.success();
        }
        finally {
            tx.close();
        }*/
    }

    protected static Options getOptions() {
        Option ontologyPath = new Option("o", "ontology-path", true, "The local location of the OWL file");
        ontologyPath.setRequired(true);
        Options options = new Options();
        options.addOption(ontologyPath);
        Option dbPath = new Option("d", "db-path", true, "The local location of the database");
        dbPath.setRequired(false);
        options.addOption(dbPath);
        return options;
    }

    protected static String determineLabel(String filename) {
        filename = filename.toLowerCase();
        if (filename.contains("disciplines")) return "DISCIPLINE";
        if (filename.contains("fairsharing")) return "DOMAIN";
        if (filename.contains("taxon")) return "SPECIES";
        else return "GENERIC";
    }

    public static void main(String[] args) {

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;
        try {
            cmd =  parser.parse(getOptions(), args);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(Owl2Neo4jLoader.class.getSimpleName(), getOptions());
            System.exit(ERR_STATUS);
        }


        try {
            System.out.println("Deleting graph database directory");
            FileUtils.deleteDirectory(new File(GRAPH_DB_PATH));
        }
        catch (IOException e) {
            System.err.println("Exception caught: " + e.getMessage());
        }

        String graphDbPath = cmd.getOptionValue("d", Owl2Neo4jLoader.GRAPH_DB_PATH);
        GraphDatabaseService graphDb = new GraphDatabaseFactory().newEmbeddedDatabase(new File(graphDbPath));
        String[] owlFiles = cmd.getOptionValues("o");

        for (String filePath : owlFiles) {
            File file = new File(filePath.trim());
            try {
                OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
                System.out.println("Preparing to load ontology from file:" + file.getAbsolutePath());
                OWLOntology ontology = manager.loadOntologyFromOntologyDocument(file);
                OWLDataFactory factory = manager.getOWLDataFactory();
                System.out.println("Loaded ontology" + ontology);
                Owl2Neo4jLoader loader = new Owl2Neo4jLoader(graphDb, ontology, factory);
                String label = determineLabel(filePath);
                System.out.println("Label is: " + label);
                loader.loadOboAlternativeTermFromOntology();
                loader.importOntology(label);
            }
            catch (Exception e) {
                System.err.println("Exception caught: " + e.getMessage());
                e.printStackTrace();
                System.exit(ERR_STATUS);
            }
        }
        /*
        String query = "MATCH (n) RETURN n ORDER BY ID(n) DESC LIMIT 30;";
        try (Result result = graphDb.execute(query)) {
            while (result.hasNext()) {
                Map<String, Object> row = result.next();
                for (String key : result.columns()) {
                    Node node = (Node) row.get(key);
                    Object name = node.getProperty("name");
                    System.out.printf("%s = %s; name = %s%n", key, row.get(key), name);
                }

            }
        }
        catch (Exception e) {
            System.err.println("Exception caught: " + e.getMessage());
            e.printStackTrace();
        } */
        System.out.println("Exiting with success...");
        System.exit(OK_STATUS);

    }



}
