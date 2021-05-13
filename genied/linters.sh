echo Run Linters: >lint.txt

# Do not show GPG popups for local tests
export LEIN_GPG=

echo Linting with bikeshed | tee -a lint.txt
lein bikeshed 2>&1 | tee -a lint.txt

echo Linting with eastwood | tee -a lint.txt
lein eastwood 2>&1 | tee -a lint.txt

echo Linting with kibit | tee -a lint.txt
lein kibit 2>&1 | tee -a lint.txt

# yagni not that useful currently.
# echo Linting with yagni | tee -a lint.txt
# lein yagni 2>&1 | tee -a lint.txt

echo Linting with clj-kondo | tee -a lint.txt
clj-kondo --lint src --lint test 2>&1 | tee -a lint.txt

echo Linting with cljfmt | tee -a lint.txt
lein cljfmt check 2>&1 | tee -a lint.txt

echo Linting with check-namespace-decls | tee -a lint.txt
lein check-namespace-decls 2>&1 | tee -a lint.txt

echo Linting with ancient | tee -a lint.txt
lein ancient 2>&1 | tee -a lint.txt

echo check lint.txt for results
