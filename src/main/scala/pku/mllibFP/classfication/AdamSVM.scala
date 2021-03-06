package pku.mllibFP.classfication

import org.apache.spark.SparkEnv
import org.apache.spark.mllib.linalg.{DenseVector, SparseVector}
import pku.mllibFP.util._
import org.apache.spark.rdd.RDD

import scala.util.Random

class AdamSVM(@transient inputRDD: RDD[ArrayWorkSet[WorkSet]],
              numFeatures: Int,
              numPartitions: Int,
              regParam: Double,
              stepSize: Double,
              numIterations: Int,
              miniBatchSize: Int) extends SVM(inputRDD, numFeatures, numPartitions,
  regParam, stepSize, numIterations, miniBatchSize) {


  override def generateModel(inputRDD: RDD[ArrayWorkSet[WorkSet]]): RDD[(ArrayWorkSet[WorkSet],
    Array[Array[Double]])] = {
    // generate model
    inputRDD.mapPartitions{
      iter => {
        // model contains the model, expectation_g2 and velocity.
        val model: Array[Array[Double]] = Array.ofDim[Double](3, numFeatures / numPartitions + 1)
        Iterator((iter.next(), model))
      }
    }
  }


  override def updateModel(model: Array[Array[Double]], arrayWorkSet: ArrayWorkSet[WorkSet],
                           interResults: Array[Array[Double]],
                           batchSize: Int, last_seed: Int, iterationId: Int): Unit ={
    val rand = new Random(last_seed)

    val epsilon = SparkEnv.get.conf.getDouble("spark.ml.sgd.adam.epsilon", 1e-7)
    val beta1 = SparkEnv.get.conf.getDouble("spark.ml.sgd.adam.beta1", 0.9)
    val beta2 = SparkEnv.get.conf.getDouble("spark.ml.sgd.adam.beta2", 0.99)
    // gradient = -y_i * x if

    val gradient: Array[Double] = new Array[Double](model(0).length) // dimension of the local model.

    for(id_batch <- 0 until batchSize){
      val tmp_data_point = arrayWorkSet.getRandomLabeledPartDataPoint(rand)
      val label_scaled = 2 * tmp_data_point.label - 1
      val margin = label_scaled * interResults(0)(id_batch)
      if(margin < 1){
        // update model
        val coeff = -label_scaled
        tmp_data_point.features match{
          case sp: SparseVector => {
            val indices = sp.indices
            val values = sp.values
            for(iid <- 0 until(indices.length)){
              gradient(indices(iid)) += coeff * values(iid)
            }
          }
          case dp: DenseVector => {
            throw new ColumnMLDenseVectorException
          }
        }
      }
      else{
        // do nothing
      }
    }

    val m_bias = 1 - math.pow(beta1, iterationId + 1)
    val v_bias = 1 - math.pow(beta2, iterationId + 1)
    for(id <- 0 until gradient.length){
      if(gradient(id) != 0) {
        gradient(id) /= batchSize // normalize
        model(1)(id) = beta1 * model(1)(id) + (1 - beta1) * gradient(id) // momentum
        model(2)(id) = beta2 * model(2)(id) + (1 - beta2) * gradient(id) * gradient(id) // v

        model(0)(id) -= stepSize / (math.sqrt(model(2)(id) / v_bias) + epsilon) * model(1)(id) / m_bias
      }
    }

  }

}
