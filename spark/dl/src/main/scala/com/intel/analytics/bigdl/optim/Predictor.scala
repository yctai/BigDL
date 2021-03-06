/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.optim

import com.intel.analytics.bigdl._
import com.intel.analytics.bigdl.dataset.{MiniBatch, Sample, SampleToMiniBatch, Utils, DataSet => _}
import com.intel.analytics.bigdl.models.utils.ModelBroadcast
import com.intel.analytics.bigdl.nn.abstractnn.Activity
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import org.apache.spark.rdd.RDD
import org.dmg.pmml.False

import scala.reflect.ClassTag

object Predictor {
  def apply[T: ClassTag](model: Module[T])(implicit ev: TensorNumeric[T]): Predictor[T] = {
    new Predictor[T](model)
  }
}

class Predictor[T: ClassTag] private[optim](
   model: Module[T])(implicit ev: TensorNumeric[T]) extends Serializable {

  private val batchPerPartition = 4

  def predictClass(dataSet: RDD[Sample[T]], batchSize: Int = -1): RDD[Int] = {
    val result = predict(dataSet, batchSize, true)
    result.mapPartitions { partition =>
      partition.map(output => {
        val _output = output.toTensor[T]
        require(_output.dim() == 1, s"Predictor.predictClass:" +
          s"Only support one sample has one lable, but got ${_output.dim()} label")
        ev.toType[Int](_output.max(1)._2.valueAt(1))
      })
    }
  }

  def predict(dataSet: RDD[Sample[T]], batchSize: Int = -1,
              shareBuffer: Boolean = false): RDD[Activity] = {
    val modelBroad = ModelBroadcast[T].broadcast(dataSet.sparkContext, model.evaluate())
    val partitionNum = dataSet.partitions.length
    val totalBatch = if (batchSize > 0) {
      require(batchSize % partitionNum == 0, s"Predictor.predict: total batch size $batchSize " +
        s"should be divided by partitionNum ${partitionNum}")
      batchSize
    } else {
      batchPerPartition * partitionNum
    }
    val otherBroad = dataSet.sparkContext.broadcast(SampleToMiniBatch(
      batchSize = totalBatch,
      partitionNum = Some(partitionNum)), shareBuffer)
    dataSet.mapPartitions { partition =>
      val localModel = modelBroad.value()
      val localTransformer = otherBroad.value._1.cloneTransformer()
      val repeatMemory = otherBroad.value._2
      val miniBatch = localTransformer(partition)
      miniBatch.flatMap( batch => {
        val output = localModel.forward(batch.getInput).toTensor[T]
        if (shareBuffer) {
          output.split(1)
        } else {
          output.clone().split(1)
        }
      })
    }
  }
}
