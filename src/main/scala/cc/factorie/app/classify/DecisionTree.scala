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

package cc.factorie.app.classify

import cc.factorie._
import cc.factorie.optimize._

class ID3DecisionTreeTrainer(implicit random: scala.util.Random) extends ClassifierTrainer {
  def train[L <: LabeledMutableDiscreteVar[_], F <: DiscreteTensorVar](il: LabelList[L, F]): ModelBasedClassifier[L, Model] = {
    val trainer = new DecisionTreeMultiClassTrainer
    val classifier = trainer.train(il, il.labelToFeatures, il.instanceWeight)
    new ModelBasedClassifier(classifier.asTemplate(il.labelToFeatures)(il.labelManifest), il.head.domain)
  }
}

class AdaBoostDecisionStumpTrainer(implicit random: scala.util.Random) extends ClassifierTrainer {
  var iterations = 1000
  def train[L <: LabeledMutableDiscreteVar[_], F <: DiscreteTensorVar](il: LabelList[L, F]): ModelBasedClassifier[L, Model] = {
    val trainer = new BoostingMultiClassTrainer(iterations)
    val classifier = trainer.train(il, il.labelToFeatures, il.instanceWeight)
    new ModelBasedClassifier(classifier.asTemplate(il.labelToFeatures)(il.labelManifest), il.head.domain)
  }
}