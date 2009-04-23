package scalanlp.sequences
/*
 Copyright 2009 David Hall, Daniel Ramage

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/

import java.util.Arrays;
import scala.collection.mutable.ArrayBuffer;
import counters._;
import counters.ints._;
import util.Index;
import math.Numerics._;
import stats.sampling._
import Math._;
import util.Implicits._;
import scalanlp.util.Lazy;
import scalanlp.util.Lazy.Implicits._;

/**
* Represents a CRF with arbitrary window size, can score sequences and such.
*
* @param features, functions of the form ([states],i,observations)=&lt;Double
* @param weights, to go with the features
* @param stateDict: a set of allowed states
* @param start: an initial state, that seq's implicitly start with.
* @param window: how wide of a window the features are over.
*/
final class CRF(val features: Seq[(Seq[Int],Int,Seq[Int])=>Double],
               val weights: Seq[Double],
               val numStates: Int,
               val start: Int,
               val validStatesForObservation: Int=>Seq[Int],
               val window: Int) {
  require(features.length == weights.length);
  require(window > 0)

  def calibrate(words: Seq[Int]) = new Calibration(words, Map());

  import CRF._;
  private val factorSize = pow(numStates,window).toInt;
  private val messageSize = pow(numStates,window-1).toInt;

  class Calibration(val words:Seq[Int], conditioning: Map[Int,Int]) { baseCalibration =>
    lazy val partition = exp(logPartition);
    lazy val logPartition = {
      logSum(leftMessages.last.scores);
    }

    lazy val logMarginals : Seq[Lazy[DoubleCounter[Int]]] = {
      (for { 
        i:Int <- (0 until words.length).toArray
      } yield Lazy.delay {
        if(conditioning.contains(i)) {
          val result = Int2DoubleCounter();
          result(conditioning(i)) = 0.0;
          result;
        } else {
          val factor = factors(i).calibrated;
          val accum = new Array[Double](numStates);
          Arrays.fill(accum,NEG_INF_DOUBLE)

          // for each possible assignment to the left and right message
          for ( (stateSeq,score) <- factor.activeElements) {
            val head = stateSeq / rightShifter; // trigram XYZ, get Z
            // sum out this contribution
            accum(head) = logSum(accum(head),factor(stateSeq));
          }

          val c = Int2DoubleCounter();
          // subtract out the log partition function.
          c ++= ( for( (v,i) <- accum.zipWithIndex
                      if !v.isInfinite) 
                    yield (i,v - logPartition)
                );
          assert(c.size > 0)
          c;
        }
      }).force
    }

    def condition(m: Map[Int,Int]) = new Calibration(words,conditioning ++ m) {
      val minChanged = m.keys.foldLeft(words.length)(_ min _);
      val maxChanged = m.keys.foldLeft(0)(_ max _ );
      override val factors = Array.range(0,words.length) map { i => 
        new Factor(i) {
          override protected def computeLeftMessage = {
            if(i < minChanged) baseCalibration.factors(i).leftMessage
            else super.computeLeftMessage
          }
          
          override protected def computeRightMessage: Calibration#Message = {
            if(i > maxChanged) baseCalibration.factors(i).rightMessage
            else super.computeRightMessage
          }
        }
      }
    }

    protected val factors = Array.range(0,words.length).map( (i:Int) => new Factor(i)).force;

    class Factor(pos: Int) {
      require(pos <= words.length);
      require(pos >= 0);

      private val cache = new SparseVector(factorSize) {
        override val default = Double.NaN;
      };

      val conditionedComponents = (pos-window +1) to pos map ( conditioning get _ );
      val anyConditioned = conditionedComponents.exists(_ != None);

      def validStates(states: Seq[Int]) = (!anyConditioned) || {
        var i = 0;
        var ok = true;
        while(i < states.length && ok) {
          ok = (conditionedComponents(i) == None || conditionedComponents(i).get == states(i))
        }
        ok;
      }

      def compute(stateSeq: Int) = {
        if(!cache(stateSeq).isNaN) {
          cache(stateSeq)
        } else { 
          var i = 0;
          var score = 0.0;
          val states = decode(stateSeq);
          if(!validStates(states)) {
            NEG_INF_DOUBLE
          } else {
            while(i < features.length && !score.isInfinite) {
              score += weights(i) * features(i)(states,pos,words);
              i += 1;
            }
            assert(!score.isNaN);
            cache(stateSeq) = score;
            score;
          }
        }
      }

      private[CRF] lazy val leftMessage: Calibration#Message = computeLeftMessage;
      
      protected def computeLeftMessage: Calibration#Message = {
        val incoming = leftMessages(pos).result;
        val outgoing = new Message(pos,pos+1);
       // println("Left Using " + incoming + " to compute " + outgoing);
        for { 
          // for each prior sequence of states  x_i,... x_{i+window}
          (stateSeq,initScore) <- incoming.scores.activeElements;
          // and for each next state x_{i+window+1}
          nextState <- validStatesFor(pos)
        } /* do */ { // sum out the x_i
          val nextStateSeq = appendRight(stateSeq, nextState);
          val outgoingAssignment = shiftRight(nextStateSeq,0);
          val score = initScore + compute(nextStateSeq);
          outgoing.scores(outgoingAssignment) = logSum(outgoing.scores(outgoingAssignment),score);
          assert(!outgoing.scores(outgoingAssignment).isNaN);
        }
        outgoing
      }

      private[CRF] lazy val rightMessage: Calibration#Message = computeRightMessage
      
      protected def computeRightMessage: Calibration#Message = {
        val incoming = rightMessages(pos).result;
        val outgoing = new Message(pos,pos-1);
      //  println("Right Using " + incoming + " to compute " + outgoing);
        for { 
          // for each future sequence of states  x_{i+1},... x_{i+window}
          (stateSeq,initScore) <- incoming.scores.activeElements;
          // and for each next state x_{i}
          nextState <- validStatesFor(pos-window+1)
        } /* do */ { // sum out the x_{i+window}
          val nextStateSeq = shiftLeft(stateSeq,nextState);
          val score = initScore + compute(nextStateSeq);
          val outgoingAssignment = shiftLeft(nextStateSeq,0) / numStates;
          outgoing.scores(outgoingAssignment) = logSum(outgoing.scores(outgoingAssignment),score);
          assert(!outgoing.scores(outgoingAssignment).isNaN);
        }
        outgoing;
      }

      private[CRF] lazy val calibrated = {
        val output = new SparseVector(factorSize);
        val left = leftMessages(pos).scores;
        val right = rightMessages(pos).scores;
        /*
        println("Calibrating " + pos + "based on " + leftMessages(pos).result + " " + rightMessages(pos).result);
        println("Left {");
        renderMessage(left);
        println("}");
        println("Right {");
        renderMessage(right);
        println("}");
        */
        for( (ls,lScore) <- left.activeElements;
              nextState <- validStatesFor(pos)) {
            val seq = appendRight(ls,nextState);
            val rs = shiftRight(seq,0);
            output(seq)  = left(ls) + right(rs);
            if(!output(seq).isInfinite) {
              output(seq) += compute(seq);
            }
        }
        output;
      }
    }

    private[CRF] case class Message(src: Int, dest: Int) {
      val scores = new SparseVector(messageSize);
    }

    private val leftMessages  : Seq[Lazy[Calibration#Message]] = {
      val firstMessage = new Message(-1,0);
      firstMessage.scores(encode(Array.make(window-1,start))) = 0.0;
      val messages = new ArrayBuffer[Lazy[Calibration#Message]];
      messages += Lazy.delay { firstMessage };
      for(f <- factors) {
        messages += Lazy.delay { f.leftMessage };
      }
      messages.take(words.length);
    }

    private lazy val rightMessages: Seq[Lazy[Calibration#Message]] = {
      val firstMessage = new Message(-1,words.length-1);
      // first message is: p(x|w) \propto 1 forall valid state sequences x
      for(seq <- 0 until messageSize;
          states = decode(seq,window-1)) {
        val allowed = states.elements.zipWithIndex forall { case(state,i) => 
          validStatesFor(words.length - window + i + 1) contains state
        }
        if(allowed) firstMessage.scores(seq) = 0.0;
      }
      val messages = new ArrayBuffer[Lazy[Calibration#Message]];
      messages += Lazy.delay { firstMessage };
      for(f <- factors.reverse) {
        messages += Lazy.delay { f.rightMessage };
      }
      messages.reverse.drop(1)
    }

    private val startArray = Array(start);
    private def validStatesFor(pos: Int) = {
      if(pos < 0) startArray;
      else validStatesForObservation(words(pos));
    }
  }

  private def renderMessage(arr: SparseVector) = {
    arr.activeElements foreach { case(seq,v) =>
      if(!v.isInfinite) {
        val states = decode(seq,window-1);
        println(states.mkString("[",",","]") + " =>  " + v);
      }
    }
  }


  // Utility stuff for decoding/encoding sequences of ints as a single Int.
  // Basic idea:
  // if there are numStates, then we can encode a sequence x_0, ..., x_{window-1}
  // as a window digit number in base numStates. 
  // 
  private def encode(s: Seq[Int]) = {
    s.foldRight(0)(_ + _ * numStates);
  }

  private def decode(s: Int):Array[Int] = decode(s,window)
    
  // could use unfoldr, but it's too slow.
  private def decode(s: Int, window: Int): Array[Int] = {
    val result = new Array[Int](window);
    var acc = s;
    var i = 0;
    while(acc != 0) {
      val r = acc % numStates;
      acc /= numStates;
      result(i) = r;
      i += 1;
    }
    assert(result.size <= window);
    result;
  }

  // TODO: decompose this into a pretty interface in another class.
  private val rightShifter = pow(numStates,window-1).toInt;
  private def shiftRight(s: Int, next: Int) = {
    s / numStates + rightShifter * next;
  }

  private def appendRight(s: Int, next:Int) = {
    assert(s < rightShifter);
    s + rightShifter * next;
  }

  private def shiftLeft(s:Int, prev: Int) = {
    s % rightShifter * numStates + prev;
  }

}

object CRF {
  class SparseVector(domainSize: Int) extends scalala.tensor.sparse.SparseVector(domainSize) {
    override val default = NEG_INF_DOUBLE;
  }
}