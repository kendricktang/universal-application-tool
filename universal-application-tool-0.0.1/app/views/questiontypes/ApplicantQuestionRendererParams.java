package views.questiontypes;

import com.google.auto.value.AutoValue;
import java.util.Optional;
import play.i18n.Messages;
import services.aws.SignedS3UploadRequest;

@AutoValue
public abstract class ApplicantQuestionRendererParams {

  public static Builder builder() {
    return new AutoValue_ApplicantQuestionRendererParams.Builder().setIsSample(false);
  }

  public static ApplicantQuestionRendererParams sample(Messages messages) {
    return builder().setIsSample(true).setMessages(messages).build();
  }

  public abstract boolean isSample();

  public abstract Messages messages();

  public abstract Optional<SignedS3UploadRequest> signedFileUploadRequest();

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder setIsSample(boolean isSample);

    public abstract Builder setMessages(Messages messages);

    public abstract Builder setSignedFileUploadRequest(
        SignedS3UploadRequest signedFileUploadRequest);

    public abstract ApplicantQuestionRendererParams build();
  }
}
