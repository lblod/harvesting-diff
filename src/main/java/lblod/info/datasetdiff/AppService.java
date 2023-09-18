package lblod.info.datasetdiff;

import lombok.extern.slf4j.Slf4j;
import mu.semte.ch.lib.dto.DataContainer;
import mu.semte.ch.lib.utils.ModelUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AppService {
    private final TaskService taskService;

    public AppService(TaskService taskService) {
        this.taskService = taskService;
    }

    @Async
    public void runAsync(String deltaEntry) {

        if (!taskService.isTask(deltaEntry))
            return;
        var task = taskService.loadTask(deltaEntry);

        if (task == null || StringUtils.isEmpty(task.getOperation())) {
            log.debug("task or operation is empty for delta entry {}", deltaEntry);
            return;
        }

        if (Constants.TASK_HARVESTING_DATASET_DIFF.equals(task.getOperation())) {
            try {
                taskService.updateTaskStatus(task, Constants.STATUS_BUSY);
                var inputContainer = taskService.selectInputContainer(task).get(0);
                log.info("input container: {}", inputContainer);
                var importedTriples = taskService.fetchTripleFromFileInputContainer(
                        inputContainer.getGraphUri());
                var fileContainer = DataContainer.builder().build();

                var graphContainer = DataContainer.builder().build();
                var resultContainer = DataContainer.builder().graphUri(graphContainer.getUri()).build();

                for (var mdb : importedTriples) {
                    var previousCompletedModel = taskService.fetchTripleFromPreviousJobs(task, mdb.derivedFrom());
                    var newInserts = ModelUtils.difference(mdb.model(), previousCompletedModel);
                    var toRemoveOld = ModelUtils.difference(previousCompletedModel, mdb.model());
                    var intersection = ModelUtils.intersection(mdb.model(), previousCompletedModel);
                    var dataDiffContainer = fileContainer.toBuilder()
                            .graphUri(taskService.writeTtlFile(
                                    task.getGraph(), newInserts, "new-insert-triples.ttl",
                                    mdb.derivedFrom()))
                            .build();
                    taskService.appendTaskResultFile(task, dataDiffContainer);

                    taskService.appendTaskResultFile(
                            task, graphContainer.toBuilder()
                                    .graphUri(dataDiffContainer.getGraphUri())
                                    .build());

                    var dataRemovalsContainer = fileContainer.toBuilder()
                            .graphUri(taskService.writeTtlFile(
                                    task.getGraph(), toRemoveOld, "to-remove-triples.ttl",
                                    mdb.derivedFrom()))
                            .build();
                    taskService.appendTaskResultFile(task, dataRemovalsContainer);

                    var dataIntersectContainer = fileContainer.toBuilder()
                            .graphUri(taskService.writeTtlFile(
                                    task.getGraph(), intersection, "intersect-triples.ttl",
                                    mdb.derivedFrom()))
                            .build();
                    taskService.appendTaskResultFile(task, dataIntersectContainer);
                    var dataContainer = DataContainer.builder()
                            .graphUri(dataDiffContainer.getGraphUri())
                            .build();
                    taskService.appendTaskResultFile(task, dataContainer);
                }

                taskService.appendTaskResultGraph(task, resultContainer);
                taskService.updateTaskStatus(task, Constants.STATUS_SUCCESS);
                log.info("Done with success for task {}", task.getId());
            } catch (Throwable e) {
                log.error("Error:", e);
                taskService.updateTaskStatus(task, Constants.STATUS_FAILED);
                taskService.appendTaskError(task, e.getMessage());
            }
        } else {
            log.debug("unknown operation '{}' for delta entry {}",
                    task.getOperation(), deltaEntry);
        }
    }
}
