export GITHUB_REF="refs/heads/master"
if [[ "$GITHUB_REF" == 'refs/heads/master'] ||  ["$GITHUB_REF" == 'refs/heads/main' ]]; then
  echo "yes"
else
  echo "Publication is running on '$GITHUB_REF', which is not the master or main branch; skipping Trickle updates"
fi
