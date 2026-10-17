#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/multi-module-repo"
rm -rf "$DIR"
mkdir -p "$DIR/module-a/src/main/java/com/example/a"
mkdir -p "$DIR/module-b/src/main/java/com/example/b"
mkdir -p "$DIR/module-b/src/test/java/com/example/b"
cd "$DIR"
git init -q
git config user.email "fixture@example.com"
git config user.name "Fixture Bot"

cat > pom.xml <<'EOF'
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>multi-module-app</artifactId>
  <version>1.0.0</version>
  <packaging>pom</packaging>
  <modules>
    <module>module-a</module>
    <module>module-b</module>
  </modules>
</project>
EOF

cat > module-a/pom.xml <<'EOF'
<project>
  <modelVersion>4.0.0</modelVersion>
  <artifactId>module-a</artifactId>
</project>
EOF

cat > module-a/src/main/java/com/example/a/Placeholder.java <<'EOF'
package com.example.a;

public class Placeholder {
}
EOF

cat > module-b/pom.xml <<'EOF'
<project>
  <modelVersion>4.0.0</modelVersion>
  <artifactId>module-b</artifactId>
</project>
EOF

cat > module-b/src/main/java/com/example/b/PricingService.java <<'EOF'
package com.example.b;

public class PricingService {

    public double applyDiscount(String customerType) {
        return customerType.length() * 0.1;
    }
}
EOF

cat > module-b/src/test/java/com/example/b/PricingServiceTest.java <<'EOF'
package com.example.b;

public class PricingServiceTest {
    public void testApplyDiscount() {
        PricingService service = new PricingService();
        service.applyDiscount(null);
    }
}
EOF

git add -A
git commit -q -m "Initial multi-module layout"

cat > module-b/src/main/java/com/example/b/PricingService.java <<'EOF'
package com.example.b;

public class PricingService {

    public double applyDiscount(String customerType) {
        // Assumes customerType is always non-null; bulk-import path can pass null.
        return customerType.length() * 0.1;
    }
}
EOF

git add -A
git commit -q -m "Add comment documenting null assumption in PricingService"

echo "Multi-module sample repo created at $DIR"
git log --oneline
