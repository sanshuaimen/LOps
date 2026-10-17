#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sample-repo"
rm -rf "$DIR"
mkdir -p "$DIR/src/main/java/com/example"
mkdir -p "$DIR/src/test/java/com/example"
cd "$DIR"
git init -q
git config user.email "fixture@example.com"
git config user.name "Fixture Bot"

cat > pom.xml <<'EOF'
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>sample-app</artifactId>
  <version>1.0.0</version>
</project>
EOF

cat > src/main/java/com/example/CustomerType.java <<'EOF'
package com.example;

public enum CustomerType {
    STANDARD(0.0), PREMIUM(0.2);

    private final double discountRate;

    CustomerType(double discountRate) {
        this.discountRate = discountRate;
    }

    public double getDiscountRate() {
        return discountRate;
    }
}
EOF

cat > src/main/java/com/example/Customer.java <<'EOF'
package com.example;

public class Customer {
    private CustomerType type;

    public Customer(CustomerType type) {
        this.type = type;
    }

    public CustomerType getType() {
        return type;
    }
}
EOF

cat > src/main/java/com/example/UserService.java <<'EOF'
package com.example;

public class UserService {

    public double calculateDiscount(Customer customer) {
        double base = 100.0;
        double rate = customer.getType().getDiscountRate();
        return base * rate;
    }
}
EOF

cat > src/test/java/com/example/UserServiceTest.java <<'EOF'
package com.example;

public class UserServiceTest {
    public void testPremiumDiscount() {
        UserService service = new UserService();
        Customer customer = new Customer(CustomerType.PREMIUM);
        service.calculateDiscount(customer);
    }
}
EOF

git add -A
git commit -q -m "Initial UserService implementation"

# Introduce the bug: bulk-import customers can reach here with no type set yet
cat > src/main/java/com/example/UserService.java <<'EOF'
package com.example;

public class UserService {

    public double calculateDiscount(Customer customer) {
        double base = 100.0;
        // Assumes getType() always returns a value; premium sign-ups created via the
        // new bulk-import path leave type unset until a follow-up job runs.
        double rate = customer.getType().getDiscountRate();
        return base * rate * bulkImportMultiplier(customer);
    }

    private double bulkImportMultiplier(Customer customer) {
        return 1.0;
    }
}
EOF

git add -A
git commit -q -m "Apply bulk-import multiplier to discount calculation"

echo "Sample repo created at $DIR"
git log --oneline
