package repository;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.ebean.Ebean;
import io.ebean.EbeanServer;
import io.ebean.SerializableConflictException;
import io.ebean.Transaction;
import io.ebean.TxScope;
import io.ebean.annotation.TxIsolation;
import java.util.List;
import java.util.Optional;
import javax.inject.Inject;
import javax.persistence.NonUniqueResultException;
import javax.persistence.RollbackException;
import models.LifecycleStage;
import models.Program;
import models.Question;
import models.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.db.ebean.EbeanConfig;
import services.program.BlockDefinition;
import services.program.ProgramDefinition;
import services.program.ProgramQuestionDefinition;

/** A repository object for dealing with versioning of questions and programs. */
public class VersionRepository {

  private final EbeanServer ebeanServer;
  private final Logger LOG = LoggerFactory.getLogger(VersionRepository.class);
  private final ProgramRepository programRepository;

  @Inject
  public VersionRepository(EbeanConfig ebeanConfig, ProgramRepository programRepository) {
    this.ebeanServer = Ebean.getServer(checkNotNull(ebeanConfig).defaultServer());
    this.programRepository = checkNotNull(programRepository);
  }

  /**
   * Publish a new version of all programs and all questions. All DRAFT programs will become ACTIVE,
   * and all ACTIVE programs without a draft will be copied to the next version.
   */
  public void publishNewSynchronizedVersion() {
    try {
      ebeanServer.beginTransaction();
      Version draft = getDraftVersion();
      Version active = getActiveVersion();
      Preconditions.checkState(
          draft.getPrograms().size() > 0, "Must have at least 1 program in the draft version.");
      active.getPrograms().stream()
          .filter(
              activeProgram ->
                  draft.getPrograms().stream()
                      .noneMatch(
                          draftProgram ->
                              activeProgram
                                  .getProgramDefinition()
                                  .adminName()
                                  .equals(draftProgram.getProgramDefinition().adminName())))
          .forEach(
              activeProgramNotInDraft -> {
                activeProgramNotInDraft.addVersion(draft);
                activeProgramNotInDraft.save();
              });
      active.getQuestions().stream()
          .filter(
              activeQuestion ->
                  draft.getQuestions().stream()
                      .noneMatch(
                          draftQuestion ->
                              activeQuestion
                                  .getQuestionDefinition()
                                  .getName()
                                  .equals(draftQuestion.getQuestionDefinition().getName())))
          .forEach(
              activeQuestionNotInDraft -> {
                activeQuestionNotInDraft.addVersion(draft);
                activeQuestionNotInDraft.save();
              });
      active.setLifecycleStage(LifecycleStage.OBSOLETE);
      draft.setLifecycleStage(LifecycleStage.ACTIVE);
      active.save();
      draft.save();
      draft.refresh();
      ebeanServer.commitTransaction();
    } finally {
      ebeanServer.endTransaction();
    }
  }

  /** Get the current draft version. Creates it if one does not exist. */
  public Version getDraftVersion() {
    Optional<Version> version =
        ebeanServer
            .find(Version.class)
            .where()
            .eq("lifecycle_stage", LifecycleStage.DRAFT)
            .findOneOrEmpty();
    if (version.isPresent()) {
      return version.get();
    } else {
      // Suspends any existing thread-local transaction if one exists.
      // This method is often called by two portions of the same outer transaction,
      // microseconds apart.  It's extremely important that there only ever be one
      // draft version, so we need the highest transaction isolation level -
      // `SERIALIZABLE` means that the two transactions run as if each transaction
      // was the only transaction running on the whole database.  That is, if any
      // other code accesses these rows or executes any query which would modify them,
      // the transaction is rolled back (a RollbackException is thrown).  We
      // are forced to retry.  This is expensive in relative terms, but new drafts
      // are very rare.  It is unlikely this will represent a real performance penalty
      // for any applicant - or even any admin, really.
      Transaction transaction =
          ebeanServer.beginTransaction(
              TxScope.requiresNew().setIsolation(TxIsolation.SERIALIZABLE));
      try {
        Version newDraftVersion = new Version(LifecycleStage.DRAFT);
        ebeanServer.insert(newDraftVersion);
        ebeanServer
            .find(Version.class)
            .forUpdate()
            .where()
            .eq("lifecycle_stage", LifecycleStage.DRAFT)
            .findOne();
        transaction.commit();
        return newDraftVersion;
      } catch (NonUniqueResultException | SerializableConflictException | RollbackException e) {
        transaction.rollback(e);
        // We must end the transaction here since we are going to recurse and try again.
        // We cannot have this transaction on the thread-local transaction stack when that
        // happens.
        transaction.end();
        return getDraftVersion();
      } finally {
        // This may come after a prior call to `transaction.end` in the event of a
        // precondition failure - this is okay, since it a double-call to `end` on
        // a particular transaction.  Only double calls to ebeanServer.endTransaction
        // must be avoided.
        transaction.end();
      }
    }
  }

  public Version getActiveVersion() {
    return ebeanServer
        .find(Version.class)
        .where()
        .eq("lifecycle_stage", LifecycleStage.ACTIVE)
        .findOne();
  }

  private Optional<Question> getLatestVersionOfQuestion(long questionId) {
    String questionName =
        ebeanServer.find(Question.class).setId(questionId).select("name").findSingleAttribute();
    Optional<Question> draftQuestion =
        getDraftVersion().getQuestions().stream()
            .filter(question -> question.getQuestionDefinition().getName().equals(questionName))
            .findFirst();
    if (draftQuestion.isPresent()) {
      return draftQuestion;
    }
    return getActiveVersion().getQuestions().stream()
        .filter(question -> question.getQuestionDefinition().getName().equals(questionName))
        .findFirst();
  }

  /**
   * For each question in this program, check whether it is the most up-to-date version of the
   * question which is either DRAFT or ACTIVE. If it is not, update the pointer to the most
   * up-to-date version of the question, using the given transaction. This method can only be called
   * on a draft program.
   */
  public void updateQuestionVersions(Program draftProgram) {
    Preconditions.checkArgument(isInactive(draftProgram), "input program must not be active.");
    Preconditions.checkArgument(
        isDraft(draftProgram), "input program must be in the current draft version.");
    ProgramDefinition.Builder updatedDefinition =
        draftProgram.getProgramDefinition().toBuilder().setBlockDefinitions(ImmutableList.of());
    for (BlockDefinition block : draftProgram.getProgramDefinition().blockDefinitions()) {
      LOG.trace("Updating block {}.", block.id());
      updatedDefinition.addBlockDefinition(updateQuestionVersions(block));
    }
    draftProgram = new Program(updatedDefinition.build());
    LOG.trace("Submitting update.");
    ebeanServer.update(draftProgram);
  }

  public boolean isInactive(Question question) {
    return !getActiveVersion().getQuestions().stream()
        .anyMatch(activeQuestion -> activeQuestion.id.equals(question.id));
  }

  public boolean isInactive(Program program) {
    return !getActiveVersion().getPrograms().stream()
        .anyMatch(activeProgram -> activeProgram.id.equals(program.id));
  }

  public boolean isDraft(Question question) {
    return getDraftVersion().getQuestions().stream()
        .anyMatch(draftQuestion -> draftQuestion.id.equals(question.id));
  }

  public boolean isDraft(Program program) {
    return getDraftVersion().getPrograms().stream()
        .anyMatch(draftProgram -> draftProgram.id.equals(program.id));
  }

  private BlockDefinition updateQuestionVersions(BlockDefinition block) {
    BlockDefinition.Builder updatedBlock =
        block.toBuilder().setProgramQuestionDefinitions(ImmutableList.of());
    for (ProgramQuestionDefinition question : block.programQuestionDefinitions()) {
      Optional<Question> updatedQuestion = getLatestVersionOfQuestion(question.id());
      LOG.error(
          "Updating question ID {} to new ID {}.", question.id(), updatedQuestion.orElseThrow().id);
      updatedBlock.addQuestion(
          ProgramQuestionDefinition.create(updatedQuestion.orElseThrow().getQuestionDefinition()));
    }
    return updatedBlock.build();
  }

  public void updateProgramsForNewDraftQuestion(long oldId) {
    getDraftVersion().getPrograms().stream()
        .filter(program -> program.getProgramDefinition().hasQuestion(oldId))
        .forEach(
            program -> {
              LOG.error("Update the draft program: " + program);
              updateQuestionVersions(program);
            });

    getActiveVersion().getPrograms().stream()
        .filter(program -> program.getProgramDefinition().hasQuestion(oldId))
        .filter(
            program ->
                getDraftVersion()
                    .getProgramByName(program.getProgramDefinition().adminName())
                    .isEmpty())
        .forEach(
            program -> {
              LOG.error("Update the draft for active program: " + program);
              programRepository.createOrUpdateDraft(program);
            });
  }

  public void updateProgramsForNewDraftQuestions(ImmutableList<Long> oldQuestionIds) {
    getDraftVersion().getPrograms().stream()
        .filter(program -> program.getProgramDefinition().hasQuestions(oldQuestionIds))
        .forEach(
            program -> {
              LOG.error("Update the draft program: " + program);
              updateQuestionVersions(program);
            });

    getActiveVersion().getPrograms().stream()
        .filter(program -> program.getProgramDefinition().hasQuestions(oldQuestionIds))
        .filter(
            program ->
                getDraftVersion()
                    .getProgramByName(program.getProgramDefinition().adminName())
                    .isEmpty())
        .forEach(
            program -> {
              LOG.error("Update the draft for active program: " + program);
              programRepository.createOrUpdateDraft(program);
            });
  }

  public List<Version> listAllVersions() {
    return ebeanServer.find(Version.class).findList();
  }

  public void setLive(long versionId) {
    Version draftVersion = getDraftVersion();
    Version activeVersion = getActiveVersion();
    Version newActiveVersion = ebeanServer.find(Version.class).setId(versionId).findOne();
    newActiveVersion.setLifecycleStage(LifecycleStage.ACTIVE);
    newActiveVersion.save();
    activeVersion.setLifecycleStage(LifecycleStage.OBSOLETE);
    activeVersion.save();
    draftVersion.setLifecycleStage(LifecycleStage.DELETED);
    draftVersion.save();
  }
}
