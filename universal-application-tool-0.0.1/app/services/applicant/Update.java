package services.applicant;

import com.google.auto.value.AutoValue;
import services.Path;

@AutoValue
public abstract class Update {

  public static Update create(Path path, String value) {
    return new AutoValue_Update(path, value);
  }

  /**
   * A JSON-style path pointing to a scalar value to update in the applicant's {@link
   * ApplicantData}.
   */
  public abstract Path path();

  /** The value to update the the applicant's {@link ApplicantData} to. */
  public abstract String value();
}
