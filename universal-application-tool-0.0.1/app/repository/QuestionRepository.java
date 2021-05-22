package repository;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.CompletableFuture.supplyAsync;

import com.google.common.collect.ImmutableList;
import io.ebean.Ebean;
import io.ebean.EbeanServer;
import io.ebean.Transaction;
import io.ebean.TxScope;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Provider;
import models.Question;
import models.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.db.ebean.EbeanConfig;
import services.question.exceptions.UnsupportedQuestionTypeException;
import services.question.types.QuestionDefinition;
import services.question.types.QuestionDefinitionBuilder;

public class QuestionRepository {

  private final EbeanServer ebeanServer;
  private final DatabaseExecutionContext executionContext;
  private final Provider<VersionRepository> versionRepositoryProvider;

  Logger logger = LoggerFactory.getLogger(this.getClass());

  @Inject
  public QuestionRepository(
      EbeanConfig ebeanConfig,
      DatabaseExecutionContext executionContext,
      Provider<VersionRepository> versionRepositoryProvider) {
    this.ebeanServer = Ebean.getServer(checkNotNull(ebeanConfig).defaultServer());
    this.executionContext = checkNotNull(executionContext);
    this.versionRepositoryProvider = checkNotNull(versionRepositoryProvider);
  }

  public CompletionStage<Set<Question>> listQuestions() {
    return supplyAsync(() -> ebeanServer.find(Question.class).findSet(), executionContext);
  }

  public CompletionStage<Optional<Question>> lookupQuestion(long id) {
    return supplyAsync(
        () -> ebeanServer.find(Question.class).setId(id).findOneOrEmpty(), executionContext);
  }

  /**
   * Find and update the draft of the question with this name, if one already exists. Create a new
   * draft if there isn't one.
   */
  public Question updateOrCreateDraft(QuestionDefinition definition) {
    Transaction transaction = ebeanServer.beginTransaction(TxScope.requiresNew());
    ImmutableList<Long> oldQuestionIds = updateOrCreateDraftQuestions(definition);

    logger.error("Updating programs for question: " + oldQuestionIds);
    transaction.setNestedUseSavepoint();
    versionRepositoryProvider.get().updateProgramsForNewDraftQuestions(oldQuestionIds);

    transaction.commit();
    return versionRepositoryProvider.get().getDraftVersion().getQuestions().stream()
        .filter(question -> question.getQuestionDefinition().getName().equals(definition.getName()))
        .findFirst()
        .get();
  }

  /** Returns the list of old question ids that need to be updated in programs. */
  private ImmutableList<Long> updateOrCreateDraftQuestions(QuestionDefinition definition) {
    Version draftVersion = versionRepositoryProvider.get().getDraftVersion();
    try (Transaction transaction = ebeanServer.beginTransaction(TxScope.required())) {
      Optional<Question> existingDraft = draftVersion.getQuestionByName(definition.getName());
      try {
        if (existingDraft.isPresent()) {
          // If the question is a draft, then we don't need to recursively update references to it
          // in its repeated questions.
          // And there are no programs that need to be updated.
          Question updatedDraft =
              new Question(
                  new QuestionDefinitionBuilder(definition).setId(existingDraft.get().id).build());
          this.updateQuestionSync(updatedDraft);
          transaction.commit();
          return ImmutableList.of();
        } else {
          // If a new draft is being created, then programs referencing this question id will need
          // to be updated.
          ImmutableList.Builder<Long> oldQuestionIds = ImmutableList.builder();
          oldQuestionIds.add(definition.getId());

          Question newDraft =
              new Question(new QuestionDefinitionBuilder(definition).setId(null).build());
          insertQuestionSync(newDraft);
          newDraft.addVersion(draftVersion);
          newDraft.save();

          // If a new draft of an enumerator question is being created, then we need to recursively
          // update references to the new draft in its repeated questions.
          if (definition.isEnumerator()) {
            logger.error("Updating repeated questions for enumerator: " + definition.getName());
            transaction.setNestedUseSavepoint();
            oldQuestionIds.addAll(updateAllRepeatedQuestions(newDraft.id, definition.getId()));
          }
          transaction.commit();
          return oldQuestionIds.build();
        }
      } catch (UnsupportedQuestionTypeException e) {
        // This should not be able to happen since the provided question definition is inherently
        // valid.
        // Throw runtime exception so callers don't have to deal with it.
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Updates the enumerator id references for draft and active questions that use that reference.
   * Returns the repeated question ids that need to be updated in programs.
   */
  private ImmutableList<Long> updateAllRepeatedQuestions(
      long newEnumeratorId, long oldEnumeratorId) {
    ImmutableList.Builder<Long> oldQuestionIds = ImmutableList.builder();
    Stream.concat(
            versionRepositoryProvider.get().getDraftVersion().getQuestions().stream(),
            versionRepositoryProvider.get().getActiveVersion().getQuestions().stream())
        .filter(
            question ->
                question
                    .getQuestionDefinition()
                    .getEnumeratorId()
                    .equals(Optional.of(oldEnumeratorId)))
        .forEach(
            question -> {
              try {
                logger.error(
                    "Updating enumerator id for draft question: "
                        + question.getQuestionDefinition().getName());
                oldQuestionIds.addAll(
                    updateOrCreateDraftQuestions(
                        new QuestionDefinitionBuilder(question.getQuestionDefinition())
                            .setEnumeratorId(Optional.of(newEnumeratorId))
                            .build()));
              } catch (UnsupportedQuestionTypeException e) {
                throw new RuntimeException(e);
              }
            });
    return oldQuestionIds.build();
  }

  /**
   * Maybe find a {@link Question} that conflicts with {@link QuestionDefinition}.
   *
   * <p>This is intended to be used for new question definitions, since updates will collide with
   * themselves and previous versions, and new versions of an old question will conflict with the
   * old question.
   *
   * <p>Questions collide if they share a {@link QuestionDefinition#getQuestionPathSegment()} and
   * {@link QuestionDefinition#getEnumeratorId()}.
   */
  public Optional<Question> findConflictingQuestion(QuestionDefinition newQuestionDefinition) {
    ConflictDetector conflictDetector =
        new ConflictDetector(
            newQuestionDefinition.getEnumeratorId(),
            newQuestionDefinition.getQuestionPathSegment(),
            newQuestionDefinition.getName());
    ebeanServer
        .find(Question.class)
        .findEachWhile(question -> !conflictDetector.hasConflict(question));
    return conflictDetector.getConflictedQuestion();
  }

  private static class ConflictDetector {
    private Optional<Question> conflictedQuestion = Optional.empty();
    private final Optional<Long> enumeratorId;
    private final String questionPathSegment;
    private final String questionName;

    private ConflictDetector(
        Optional<Long> enumeratorId, String questionPathSegment, String questionName) {
      this.enumeratorId = checkNotNull(enumeratorId);
      this.questionPathSegment = checkNotNull(questionPathSegment);
      this.questionName = checkNotNull(questionName);
    }

    private Optional<Question> getConflictedQuestion() {
      return conflictedQuestion;
    }

    private boolean hasConflict(Question question) {
      if (question.getQuestionDefinition().getName().equals(questionName)
          || (question.getQuestionDefinition().getEnumeratorId().equals(enumeratorId)
              && question
                  .getQuestionDefinition()
                  .getQuestionPathSegment()
                  .equals(questionPathSegment))) {
        conflictedQuestion = Optional.of(question);
        return true;
      }
      return false;
    }
  }

  public CompletionStage<Question> insertQuestion(Question question) {
    return supplyAsync(
        () -> {
          ebeanServer.insert(question);
          return question;
        },
        executionContext);
  }

  public Question insertQuestionSync(Question question) {
    ebeanServer.insert(question);
    return question;
  }

  public CompletionStage<Question> updateQuestion(Question question) {
    return supplyAsync(
        () -> {
          ebeanServer.update(question);
          return question;
        },
        executionContext);
  }

  public Question updateQuestionSync(Question question) {
    ebeanServer.update(question);
    return question;
  }
}
