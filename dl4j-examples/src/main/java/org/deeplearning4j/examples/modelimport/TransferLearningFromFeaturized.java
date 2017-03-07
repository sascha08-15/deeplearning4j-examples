package org.deeplearning4j.examples.modelimport;

import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.api.storage.StatsStorage;
import org.deeplearning4j.datasets.iterator.AsyncDataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.Updater;
import org.deeplearning4j.nn.conf.layers.OutputLayer;
import org.deeplearning4j.nn.graph.ComputationGraph;
import org.deeplearning4j.nn.modelimport.keras.InvalidKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.UnsupportedKerasConfigurationException;
import org.deeplearning4j.nn.modelimport.keras.trainedmodels.TrainedModelHelper;
import org.deeplearning4j.nn.modelimport.keras.trainedmodels.TrainedModels;
import org.deeplearning4j.nn.transferlearning.FineTuneConfiguration;
import org.deeplearning4j.nn.transferlearning.TransferLearning;
import org.deeplearning4j.nn.transferlearning.TransferLearningHelper;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.ui.api.UIServer;
import org.deeplearning4j.ui.stats.StatsListener;
import org.deeplearning4j.ui.storage.InMemoryStatsStorage;
import org.deeplearning4j.util.ModelSerializer;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.ExistingMiniBatchDataSetIterator;
import org.nd4j.linalg.dataset.api.iterator.DataSetIterator;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.io.File;
import java.io.IOException;

/**
 * Created by susaneraly on 3/1/17.
 */
@Slf4j
public class TransferLearningFromFeaturized {

    protected static final int numClasses = 5;

    protected static final long seed = 12345;
    protected static final int nEpochs = 50;

    public static void main(String [] args) throws UnsupportedKerasConfigurationException, IOException, InvalidKerasConfigurationException {

        /*
            Step I: Construct the architecture we want from vgg16

            We set "fc1" and below to frozen (this is what we did when we presaved the dataset and this has to line up)
            We then change nOut for "fc2" to be 1024. This reinitializes this layer with the weight init given
            We then remove the existing output layer and connections
            Add in a new dense layer "fc3" followed by another new dense layer "newpredictions"
            Set outputs and loss functions correctly
            The settings from the fine tune configuration will be applied to all layers that are not frozen
                unless they are individualy overriden (like the activation softmax in the output layer that
                                                overrides the leaky relu activation from the fine tune)
         */
        TrainedModelHelper modelImportHelper = new TrainedModelHelper(TrainedModels.VGG16);
        ComputationGraph vgg16 = modelImportHelper.loadModel();
        log.info(vgg16.summary());

        ComputationGraph vgg16Transfer = new TransferLearning.GraphBuilder(vgg16)
            .fineTuneConfiguration(new FineTuneConfiguration.Builder()
                                        .activation(Activation.LEAKYRELU)
                                        .learningRate(5e-5)
                                        .optimizationAlgo(OptimizationAlgorithm.STOCHASTIC_GRADIENT_DESCENT)
                                        .updater(Updater.NESTEROVS)
                                        //.regularization(true).l2(0.001)
                                        .dropOut(0.5)
                                        .seed(seed)
                                        .build())
            .setFeatureExtractor("fc1") //this is where we featurized our dataset
            .nOutReplace("fc2",1024, WeightInit.XAVIER)
            .removeVertexAndConnections("predictions")
            //.addLayer("fc3",new DenseLayer.Builder().nIn(1024).nOut(256).build(),"fc2")
            .addLayer("newpredictions",new OutputLayer
                                        .Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                                        .activation(Activation.SOFTMAX)
                                        .nIn(1024)
                                        .nOut(numClasses)
                                        .build(),"fc2")
            .setOutputs("newpredictions")
            .build();
        log.info(vgg16Transfer.summary());
        //vgg16Transfer.setListeners(new ScoreIterationListener(10));

        /*
            Step II: Set up a dataset iterator from the pre saved dataset
         */
        DataSetIterator existingTrainingData = new ExistingMiniBatchDataSetIterator(new File("trainFolder"),"flowers-train-%d.bin");
        DataSetIterator asyncTrainIter = new AsyncDataSetIterator(existingTrainingData);

        DataSetIterator existingTestData = new ExistingMiniBatchDataSetIterator(new File("testFolder"),"flowers-test-%d.bin");
        DataSetIterator asyncTestIter = new AsyncDataSetIterator(existingTestData);

        DataSet validationSet = existingTestData.next(100);
        /*
            Step III: Use the transfer learning helper to fit featurized
         */
        TransferLearningHelper transferLearningHelper = new TransferLearningHelper(vgg16Transfer);
        Evaluation eval = new Evaluation(numClasses);
        while(asyncTestIter.hasNext()){
            DataSet ds = asyncTestIter.next();
            INDArray output = transferLearningHelper.unfrozenGraph().output(false, ds.getFeatures())[0];
            eval.eval(ds.getLabels(), output);

        }
        log.info(eval.stats());
        asyncTestIter.reset();

        /*
            UI
         */
        //Initialize the user interface backend
        UIServer uiServer = UIServer.getInstance();
        //Configure where the network information (gradients, activations, score vs. time etc) is to be stored
        //Then add the StatsListener to collect this information from the network, as it trains
        StatsStorage statsStorage = new InMemoryStatsStorage();             //Alternative: new FileStatsStorage(File) - see UIStorageExample
        int listenerFrequency = 1;
        vgg16Transfer.setListeners(new StatsListener(statsStorage, listenerFrequency));
        //Attach the StatsStorage instance to the UI: this allows the contents of the StatsStorage to be visualized
        uiServer.attach(statsStorage);

        for( int i = 0; i < nEpochs; i++ ) {
            transferLearningHelper.fitFeaturized(asyncTrainIter);
            asyncTrainIter.reset();
            log.info("*** Completed epoch {} ***", i);
            log.info("Evaluate model....");
            eval = new Evaluation(numClasses);
            while(asyncTestIter.hasNext()){
                DataSet ds = asyncTestIter.next();
                INDArray output = transferLearningHelper.unfrozenGraph().output(false, ds.getFeatures())[0];
                eval.eval(ds.getLabels(), output);

            }
            log.info(eval.stats());
            asyncTestIter.reset();
        }
        //Save the model
        File locationToSave = new File("MyComputationGraph.zip");       //Where to save the network. Note: the file is in .zip format - can be opened externally
        boolean saveUpdater = false;                                             //Updater: i.e., the state for Momentum, RMSProp, Adagrad etc. Save this if you want to train your network more in the future
        ModelSerializer.writeModel(vgg16Transfer, locationToSave, saveUpdater);
    }
}