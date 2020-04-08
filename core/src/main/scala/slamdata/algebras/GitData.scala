package precog.algebras

import github4s.GithubResponses.GHResponse
import github4s.domain.Ref

trait GitData[F[_]] extends github4s.algebras.GitData[F] {
  /**
   * Delete a Reference
   *
   * The ref in the URL must be fully qualified.
   * For example, the call to delete `master` branch will be `refs/heads/master`.
   *
   * @param owner   of the repo
   * @param repo    name of the repo
   * @param ref     The name of the fully qualified reference (ie: refs/heads/master).
   *                If it doesn't start with 'refs' and have at least two slashes, it will be rejected.
   * @param headers optional user headers to include in the request
   * @return a GHResponse with the Ref
   */
  def deleteReference(owner: String, repo: String, ref: String, headers: Map[String, String] = Map()): F[GHResponse[Unit]]
}
