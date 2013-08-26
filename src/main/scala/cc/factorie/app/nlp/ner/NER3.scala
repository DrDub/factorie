/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie.app.nlp.ner
import cc.factorie._
import app.strings._
import cc.factorie.util.{CmdOptions, HyperparameterMain, BinarySerializer}
import cc.factorie.app.nlp.pos.PTBPosLabel
import optimize._
import cc.factorie.app.nlp._
import cc.factorie.app.chain._
import scala.io._
import java.io._
import scala.math.round


class TokenSequence(token : Token) extends collection.mutable.ArrayBuffer[Token] {
  this.prepend(token)
  val label : String = token.attr[BilouConllNerLabel].categoryValue.split("-")(1)
  def key = this.mkString("-")
} 

class NER3 extends DocumentAnnotator {

  def process(document:Document): Document = {
    if (document.tokenCount == 0) return document
    if (!document.tokens.head.attr.contains(classOf[BilouConllNerLabel]))
      document.tokens.map(token => token.attr += new BilouConllNerLabel(token, "O"))
    if (!document.tokens.head.attr.contains(classOf[ChainNerFeatures])) {
      document.tokens.map(token => token.attr += new ChainNerFeatures(token))
      initFeatures(document,(t:Token)=>t.attr[ChainNerFeatures])
    }
    process(document, useModel2 = false)
    if (!document.tokens.head.attr.contains(classOf[ChainNer2Features])) {
      document.tokens.map(token => token.attr += new ChainNer2Features(token))
      initFeatures(document,(t:Token)=>t.attr[ChainNer2Features])
      initSecondaryFeatures(document)
    }
    process(document,useModel2 = true)
    document
  }
  def prereqAttrs = Seq(classOf[Sentence], classOf[PTBPosLabel])
  def postAttrs = Seq(classOf[BilouConllNerLabel])
  def tokenAnnotationString(token:Token): String = token.attr[BilouConllNerLabel].categoryValue

  object ChainNer2FeaturesDomain extends CategoricalTensorDomain[String]
  class ChainNer2Features(val token:Token) extends BinaryFeatureVectorVariable[String] {
    def domain = ChainNer2FeaturesDomain
    override def skipNonCategories = true
  }
  object ChainNerFeaturesDomain extends CategoricalTensorDomain[String]
  class ChainNerFeatures(val token:Token) extends BinaryFeatureVectorVariable[String] {
    def domain = ChainNerFeaturesDomain
    override def skipNonCategories = true
  }


  val model = new ChainModel[BilouConllNerLabel,ChainNerFeatures,Token](BilouConllNerDomain, ChainNerFeaturesDomain, l => l.token.attr[ChainNerFeatures], l => l.token, t => t.attr[BilouConllNerLabel])
  val model2 = new ChainModel[BilouConllNerLabel,ChainNer2Features,Token](BilouConllNerDomain, ChainNer2FeaturesDomain, l => l.token.attr[ChainNer2Features], l => l.token, t => t.attr[BilouConllNerLabel])
  val objective = new ChainNerObjective

  def serialize(stream: OutputStream) {
    import cc.factorie.util.CubbieConversions._
    val is = new DataOutputStream(stream)
    BinarySerializer.serialize(ChainNerFeaturesDomain.dimensionDomain, is)
    BinarySerializer.serialize(ChainNer2FeaturesDomain.dimensionDomain, is)
    BinarySerializer.serialize(model, is)
    BinarySerializer.serialize(model2, is)
    is.close()
  }

  def deSerialize(stream: InputStream) {
    import cc.factorie.util.CubbieConversions._
    val is = new DataInputStream(stream)
    BinarySerializer.deserialize(ChainNerFeaturesDomain.dimensionDomain, is)
    BinarySerializer.deserialize(ChainNer2FeaturesDomain.dimensionDomain, is)
    BinarySerializer.deserialize(model, is)
    BinarySerializer.deserialize(model2, is)
    is.close()
  }


  import cc.factorie.app.nlp.lexicon._
  val lexicons = Seq(
    iesl.AllPlaces,
    iesl.City,
    iesl.Company,
    iesl.Continents,
    iesl.Country,
    iesl.Day,
    iesl.JobTitle,
    iesl.Month,
    iesl.OrgSuffix,
    iesl.PersonFirst,
    iesl.PersonFirstHigh,
    iesl.PersonFirstHighest,
    iesl.PersonFirstMedium,
    iesl.PersonHonorific,
    iesl.PersonLast,
    iesl.PersonLastHigh,
    iesl.PersonLastHighest,
    iesl.PersonLastMedium,
    iesl.USState,
    wikipedia.Book,
    wikipedia.Business,
    wikipedia.Event,
    wikipedia.Film,
    wikipedia.LocationAndRedirect,
    wikipedia.OrganizationAndRedirect,
    wikipedia.PersonAndRedirect)

  var aggregate = false
  var twoStage = false
  val clusters = new scala.collection.mutable.HashMap[String,String]
  var count = 0
  var didagg = false
  var bP = false
  var ss = 10.0

  def prevWindowNum(t:Token, n:Int): IndexedSeq[(Int,Token)] = t.prevWindow(n).map(x => (t.prevWindow(n).indexOf(x),x)).toIndexedSeq
  def nextWindowNum(t:Token, n:Int): IndexedSeq[(Int,Token)] = t.nextWindow(n).map(x => (t.nextWindow(n).indexOf(x),x)).toIndexedSeq

  def prefix( prefixSize : Int, cluster : String ) : String = if(cluster.size > prefixSize) cluster.substring(0, prefixSize) else cluster

  def addContextFeatures[A<:Observation[A]](t : Token, from : Token, vf:Token=>CategoricalTensorVar[String]) : Unit = {
    vf(t) ++= prevWindowNum(from,2).map(t2 => "CONTEXT="+simplifyDigits(t2._2.string).toLowerCase + "@-" + t2._1)
    vf(t) ++= nextWindowNum(from, 2).map(t2 => "CONTEXT="+simplifyDigits(t2._2.string).toLowerCase + "@" + t2._1)
    for(t2 <- prevWindowNum(from,2)) {
	    if(clusters.contains(t2._2.string)) {
			  vf(t) += ("CONTEXTPATH="+prefix(4, clusters(t2._2.string)) + ("@-" + t2._1.toString))
    	}
    }

    for(t2 <- nextWindowNum(from, 2)) {
	    if(clusters.contains(t2._2.string))
			  vf(t) += ("CONTEXTPATH="+prefix(4, clusters(t2._2.string)) + ("@" + t2._1.toString))
    }
  }
  
  def aggregateContext[A<:Observation[A]](token : Token, vf:Token=>CategoricalTensorVar[String]) : Unit = {
    var count = 0
    var compareToken : Token = token
    while(count < 200 && compareToken.hasPrev) {
      count += 1
      compareToken = compareToken.prev
      if(token.string.toLowerCase == compareToken.string.toLowerCase)
        addContextFeatures(token, compareToken, vf)
    }
    count = 0
    compareToken = token
    while(count < 200 && compareToken.hasNext) {
      count += 1
      compareToken = compareToken.next
      if(token.string.toLowerCase == compareToken.string.toLowerCase)
        addContextFeatures(token, compareToken, vf)
    }
  }
  

  def initFeatures(document:Document, vf:Token=>CategoricalTensorVar[String]): Unit = {
    count=count+1
    import cc.factorie.app.strings.simplifyDigits
    for (token <- document.tokens) {
	    val features = vf(token)
      val rawWord = token.string
      val word = simplifyDigits(rawWord).toLowerCase
      features += "W="+word
      if (token.isCapitalized) features += "CAPITALIZED"
      else features += "NOTCAPITALIZED"
      if (token.isPunctuation) features += "PUNCTUATION"
      if(lexicons != null) for (lexicon <- lexicons; if lexicon.containsWord(token.string)) features += "LEX="+lexicon
      if (clusters.size > 0 && clusters.contains(rawWord)) {
        features += "CLUS="+prefix(4,clusters(rawWord))
        features += "CLUS="+prefix(6,clusters(rawWord))
        features += "CLUS="+prefix(10,clusters(rawWord))
        features += "CLUS="+prefix(20,clusters(rawWord))
      }
    }
    for (sentence <- document.sentences) cc.factorie.app.chain.Observations.addNeighboringFeatureConjunctions(sentence.tokens, vf, "^[^@]*$", List(0), List(1), List(2), List(-1), List(-2))
    // Add features for character n-grams between sizes 2 and 5
    document.tokens.foreach(t => if (t.string.matches("[A-Za-z]+")) vf(t) ++= t.charNGrams(2,5).map(n => "NGRAM="+n))
    // Add features from window of 4 words before and after
    document.tokens.foreach(t => vf(t) ++= t.prevWindow(4).map(t2 => "PREVWINDOW="+simplifyDigits(t2.string).toLowerCase))
    document.tokens.foreach(t => vf(t) ++= t.nextWindow(4).map(t2 => "NEXTWINDOW="+simplifyDigits(t2.string).toLowerCase))
    if(aggregate) document.tokens.foreach( aggregateContext(_, vf) )
  }
  
  def mode(list : List[String]) : String = {
    val domainCount = new collection.mutable.HashMap[String, Int]
    for(item <- list) {
      if(domainCount.contains(item)) domainCount(item) = domainCount(item) + 1
      else domainCount(item) = 1
    }
    var maxDomain = ""
    var maxCount = 0
    for(domain <- domainCount.keys) {
      if(domainCount(domain) > maxCount) {
        maxCount = domainCount(domain)
        maxDomain = domain
      }
    }
    maxDomain
  }

  def getSequences(document : Document) : List[TokenSequence] = {
    var sequences = List[TokenSequence]()
    var seq : TokenSequence = null
    for(token <- document.tokens) {
      val categoryVal = token.attr[BilouConllNerLabel].categoryValue
      if(categoryVal.length() > 0) {
        categoryVal.substring(0,1) match {
         case "B" => seq = new TokenSequence(token)
         case "I" => if (seq != null) seq.append(token) else seq = new TokenSequence(token)
         case "U" => seq = new TokenSequence(token)
         case "L" => if (seq != null) seq.append(token) else seq = new TokenSequence(token)
         case _ => null
        }
        if(categoryVal.matches("(L|U)-\\D+")) sequences = seq :: sequences
      }
     }
    sequences
  }

  def  allSubstrings(seq: TokenSequence, length : Int) : List[String] = {
	  if(length == 0) return List[String]()
	  var list = List[String]()
	  for(i <- 0 to seq.length-length) {
		  var sub = ""
		  for(k <- i until i+length) {
			  sub += " " + seq(k).string
		  }
		  list = sub :: list
	  }
	  allSubstrings(seq, length-1) ::: list
  }


  def initSecondaryFeatures(document:Document, extraFeatures : Boolean = false): Unit = {
  
    document.tokens.foreach(t => t.attr[ChainNer2Features] ++= prevWindowNum(t,2).map(t2 => "PREVLABEL" + t2._1 + "="+t2._2.attr[BilouConllNerLabel].categoryValue))
    document.tokens.foreach(t => t.attr[ChainNer2Features] ++= prevWindowNum(t,1).map(t2 => "PREVLABELCON="+t2._2.attr[BilouConllNerLabel].categoryValue+"&"+t.string))
    for(t <- document.tokens) {
	    if(t.sentenceHasPrev) {
		    t.attr[ChainNer2Features] ++= prevWindowNum(t,2).map(t2 => "PREVLABELLCON="+t.sentencePrev.attr[BilouConllNerLabel].categoryValue+"&"+t2._2.string)
    	  t.attr[ChainNer2Features] ++= nextWindowNum(t,2).map(t2 => "PREVLABELLCON="+t.sentencePrev.attr[BilouConllNerLabel].categoryValue+"&"+t2._2.string)
	    }
    }
   
    val sequences = getSequences(document)
    val tokenToLabelMap = new scala.collection.mutable.HashMap[String, List[String]]
    val extendedPrediction = new scala.collection.mutable.HashMap[String, List[String]]
    val sequenceToLabelMap = new scala.collection.mutable.HashMap[String, List[String]]
    val subsequencesToLabelMap = new scala.collection.mutable.HashMap[String, List[String]]

    if(extraFeatures) {
      for (token <- document.tokens) {
        if(tokenToLabelMap.contains(token.string))
          tokenToLabelMap(token.string) = tokenToLabelMap(token.string) ++ List(token.attr[BilouConllNerLabel].categoryValue)
        else
          tokenToLabelMap(token.string) = List(token.attr[BilouConllNerLabel].categoryValue)
      }
	    for (seq <- sequences) {
	      if(sequenceToLabelMap.contains(seq.key))
	        sequenceToLabelMap(seq.key) = sequenceToLabelMap(seq.key) ++ List(seq.label)
	      else
	        sequenceToLabelMap(seq.key) = List(seq.label)
	    }

	    for (seq <- sequences) {
		    for(subseq <- allSubstrings(seq, seq.length)) {
		      if(subsequencesToLabelMap.contains(subseq))
		        subsequencesToLabelMap(subseq) = subsequencesToLabelMap(subseq) ++ List(seq.label)
		      else
			      subsequencesToLabelMap(seq.key) = List(seq.label)
		    }
	    }
  
  	  for (token <- document.tokens) {
        val tokenVote = tokenToLabelMap(token.string)
        token.attr[ChainNer2Features] += "CLASSIFIERLABEL="+mode(tokenVote)
    	}
    
	    for(seq <- sequences) {
		    val seqVote = sequenceToLabelMap(seq.key)
		    val seqLabelMode = mode(seqVote)
		    val subSeqVote = subsequencesToLabelMap(seq.key)
		    val subSeqLabelMode = mode(subSeqVote)
		    for(token <- seq) {
		      token.attr[ChainNer2Features] += "SEQUENCELABEL="+seqLabelMode
		      token.attr[ChainNer2Features] += "SUBSEQUENCELABEL="+subSeqLabelMode
		    }
	    }
	  }
	  for(token <- document.tokens) {
		  if(extendedPrediction.contains(token.string))
        BilouConllNerDomain.categories.map(str => token.attr[ChainNer2Features] += str + "=" + history(extendedPrediction(token.string), str) )
		  if(extendedPrediction.contains(token.string))
        extendedPrediction(token.string) = extendedPrediction(token.string) ++ List(token.attr[BilouConllNerLabel].categoryValue)
      else
        extendedPrediction(token.string) = List(token.attr[BilouConllNerLabel].categoryValue)
	  }

    for(token <- document.tokens) {
		  val rawWord = token.string
		  if(token.hasPrev && clusters.size > 0) {
			  if(clusters.contains(rawWord))
		      token.attr[ChainNer2Features] ++= List(4,6,10,20).map("BROWNCON="+token.prev.attr[BilouConllNerLabel].categoryValue + "&" + prefix(_,clusters(rawWord)))
			  if(token.hasNext) {
				  var nextRawWord = token.next.string
				  if(clusters.contains(nextRawWord))
					  token.attr[ChainNer2Features] ++= List(4,6,10,20).map("BROWNCON="+token.prev.attr[BilouConllNerLabel].categoryValue + "&" + prefix(_,clusters(nextRawWord)))
				  if(token.next.hasNext && clusters.contains(token.next.next.string)) {
					  nextRawWord = token.next.next.string
					  token.attr[ChainNer2Features] ++= List(4,6,10,20).map("BROWNCON="+token.prev.attr[BilouConllNerLabel].categoryValue + "&" + prefix(_,clusters(nextRawWord)))
				  }
			  }
        if(token.hasPrev) {
				  var prevRawWord = token.prev.string
				  if(clusters.contains(prevRawWord))
				    token.attr[ChainNer2Features] ++= List(4,6,10,20).map("BROWNCON="+token.prev.attr[BilouConllNerLabel].categoryValue + "&" + prefix(_,clusters(prevRawWord)))
				  if(token.prev.hasPrev && clusters.contains(token.prev.prev.string)) {
				    prevRawWord = token.prev.prev.string
				    token.attr[ChainNer2Features] ++= List(4,6,10,20).map("BROWNCON="+token.prev.attr[BilouConllNerLabel].categoryValue + "&" + prefix(_,clusters(prevRawWord)))
				  }
			  }
		  }
	  }
  }

  def history(list : List[String], category : String) : String = {
	  (round( 10.0 * ((list.count(_ == category).toDouble / list.length.toDouble)/3)) / 10.0).toString
  }

  def train(trainFilename:String, testFilename:String, l1: Double, l2: Double, rate: Double, delta: Double): Double = {
    implicit val random = new scala.util.Random(0)
    // Read in the data
    val trainDocuments = LoadConll2003(BILOU=true).fromFilename(trainFilename)
    val testDocuments = LoadConll2003(BILOU=true).fromFilename(testFilename)

    // Add features for NER                 \
    println("Initializing training features")
	
  	(trainDocuments ++ testDocuments).foreach(_.tokens.map(token => token.attr += new ChainNerFeatures(token)))

    trainDocuments.foreach(initFeatures(_,(t:Token)=>t.attr[ChainNerFeatures]))
    println("Initializing testing features")
    testDocuments.foreach(initFeatures(_,(t:Token)=>t.attr[ChainNerFeatures]))
    
    //println("Example Token features")
    //println(trainDocuments(3).tokens.map(token => token.nerLabel.shortCategoryValue+" "+token.string+" "+token.attr[ChainNerFeatures].toString).mkString("\n"))
    //println("Example Test Token features")
    //println(testDocuments(1).tokens.map(token => token.nerLabel.shortCategoryValue+" "+token.string+" "+token.attr[ChainNerFeatures].toString).mkString("\n"))
    //println("Num TokenFeatures = "+ChainNerFeaturesDomain.dimensionDomain.size)
    
    // Get the variables to be inferred (for now, just operate on a subset)
    val trainLabels = trainDocuments.map(_.tokens).flatten.map(_.attr[BilouConllNerLabel]) //.take(100)
    val testLabels = testDocuments.map(_.tokens).flatten.map(_.attr[BilouConllNerLabel]) //.take(20)
 
    val vars = for(td <- trainDocuments; sentence <- td.sentences if sentence.length > 1) yield sentence.tokens.map(_.attr[BilouConllNerLabel])
    val examples = vars.map(v => new LikelihoodExample(v.toSeq, model, InferByBPChainSum))
    println("Training with " + examples.length + " examples")
    Trainer.onlineTrain(model.parameters, examples, optimizer=new AdaGrad(rate=rate, delta=delta) with ParameterAveraging, useParallelTrainer=false)
    trainDocuments.foreach(process(_, false))
    testDocuments.foreach(process(_, false))
    printEvaluation(trainDocuments, testDocuments, "FINAL 1")

    (trainDocuments ++ testDocuments).foreach( _.tokens.map(token => token.attr += new ChainNer2Features(token)))

    for(document <- trainDocuments ++ testDocuments) initFeatures(document, (t:Token)=>t.attr[ChainNer2Features])
    for(document <- trainDocuments ++ testDocuments) initSecondaryFeatures(document)
    //println(trainDocuments(3).tokens.map(token => token.nerLabel.target.categoryValue + " "+token.string+" "+token.attr[ChainNer2Features].toString).mkString("\n"))
    //println("Example Test Token features")
    //println(testDocuments(1).tokens.map(token => token.nerLabel.shortCategoryValue+" "+token.string+" "+token.attr[ChainNer2Features].toString).mkString("\n"))
    (trainLabels ++ testLabels).foreach(_.setRandomly)
    val vars2 = for(td <- trainDocuments; sentence <- td.sentences if sentence.length > 1) yield sentence.tokens.map(_.attr[BilouConllNerLabel])

    val examples2 = vars2.map(v => new LikelihoodExample(v.toSeq, model2, infer=InferByBPChainSum))
    Trainer.onlineTrain(model2.parameters, examples2, optimizer=new AdaGrad(rate=rate, delta=delta) with ParameterAveraging, useParallelTrainer=false)

    trainDocuments.foreach(process)
    testDocuments.foreach(process)
    printEvaluation(trainDocuments, testDocuments, "FINAL")
  }


   def printEvaluation(trainDocuments:Iterable[Document], testDocuments:Iterable[Document], iteration:String): Double = {
     println("TRAIN")
     println(evaluationString(trainDocuments))
     println("TEST")
     val test = evaluationString(testDocuments)
     println(test)
     test
   }
  
  def evaluationString(documents: Iterable[Document]): Double = {
    val buf = new StringBuffer
    buf.append(new LabeledDiscreteEvaluation(documents.flatMap(_.tokens.map(_.attr[BilouConllNerLabel]))))
    val segmentEvaluation = new cc.factorie.app.chain.SegmentEvaluation[BilouConllNerLabel](BilouConllNerDomain.categories.filter(_.length > 2).map(_.substring(2)), "(B|U)-", "(I|L)-")
    for (doc <- documents; sentence <- doc.sentences) segmentEvaluation += sentence.tokens.map(_.attr[BilouConllNerLabel])
    println("Segment evaluation")
    println(segmentEvaluation)
    segmentEvaluation.f1
  }
  def process(document:Document, useModel2 : Boolean): Unit = {
    if (document.tokenCount == 0) return
    for(sentence <- document.sentences if sentence.tokens.size > 0) {
      val vars = sentence.tokens.map(_.attr[BilouConllNerLabel]).toSeq
      BP.inferChainMax(vars, if(useModel2) model2 else model)
    }
  }

}

class NER3Opts extends CmdOptions {
  val trainFile =     new CmdOption("train", "eng.train", "FILE", "CoNLL formatted training file.")
  val testFile  =     new CmdOption("test",  "eng.testb", "FILE", "CoNLL formatted test file.")
  val modelDir =      new CmdOption("model", "chainner.factorie", "FILE", "File for saving or loading model.")
  val runXmlDir =     new CmdOption("run-xml", "xml", "DIR", "Directory for reading NYTimes XML data on which to run saved model.")
  val brownClusFile = new CmdOption("brown", "", "FILE", "File containing brown clusters.")
  val aggregateTokens = new CmdOption("aggregate", true, "BOOLEAN", "Turn on context aggregation feature.")
  val l1 =  new CmdOption("l1", 0.01, "DOUBLE", "L1 regularization")
  val l2 =  new CmdOption("l2", 0.01, "DOUBLE", "L2 regularization")
  val rate =  new CmdOption("rate", 0.18, "DOUBLE", "Learning rate")
  val delta =  new CmdOption("delta", 0.066, "DOUBLE", "Learning delta")
  val saveModel = new CmdOption("save-model", false, "BOOLEAN", "Whether to save the model")
}


object NER3Trainer extends HyperparameterMain {
  def evaluateParameters(args: Array[String]): Double = {
    // Parse command-line
    val opts = new NER3Opts
    opts.parse(args)
    val ner = new NER3

    ner.aggregate = opts.aggregateTokens.wasInvoked

    if( opts.brownClusFile.wasInvoked) {
          println("Reading brown cluster file " + opts.brownClusFile.value)
          for(line <- Source.fromFile(opts.brownClusFile.value).getLines()){
            val splitLine = line.split("\t")
            ner.clusters(splitLine(1)) = splitLine(0)
          }
    }
    
    val result = ner.train(opts.trainFile.value, opts.testFile.value, opts.l1.value, opts.l2.value, opts.rate.value, opts.delta.value)
    if (opts.saveModel.value) {
      ner.serialize(new FileOutputStream(opts.modelDir.value))
	  }
    result
  }
}

object NER3Optimizer {
  def main(args: Array[String]) {
    val opts = new NER3Opts
    opts.parse(args)
    opts.saveModel.setValue(false)
    val l1 = cc.factorie.util.HyperParameter(opts.l1, new cc.factorie.util.LogUniformDoubleSampler(1e-10, 1e2))
    val l2 = cc.factorie.util.HyperParameter(opts.l2, new cc.factorie.util.LogUniformDoubleSampler(1e-10, 1e2))
    val rate = cc.factorie.util.HyperParameter(opts.rate, new cc.factorie.util.LogUniformDoubleSampler(1e-4, 1e4))
    val delta = cc.factorie.util.HyperParameter(opts.delta, new cc.factorie.util.LogUniformDoubleSampler(1e-4, 1e4))
    /*
    val ssh = new cc.factorie.util.SSHActorExecutor("apassos",
      Seq("avon1", "avon2"),
      "/home/apassos/canvas/factorie-test",
      "try-log/",
      "cc.factorie.app.nlp.parse.DepParser2",
      10, 5)
      */
    val qs = new cc.factorie.util.QSubExecutor(60, "cc.factorie.app.nlp.ner.NER3Trainer")
    val optimizer = new cc.factorie.util.HyperParameterSearcher(opts, Seq(l1, l2, rate, delta), qs.execute, 200, 180, 60)
    val result = optimizer.optimize()
    println("Got results: " + result.mkString(" "))
    println("Best l1: " + opts.l1.value + " best l2: " + opts.l2.value)
    opts.saveModel.setValue(true)
    println("Running best configuration...")
    import scala.concurrent.duration._
    import scala.concurrent.Await
    Await.result(qs.execute(opts.values.flatMap(_.unParse).toArray), 5.hours)
    println("Done")
  }
}
