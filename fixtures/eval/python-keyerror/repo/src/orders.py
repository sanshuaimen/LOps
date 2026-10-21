CAMPAIGNS = {
    "gold": {"rate": 0.10},
    "bronze": {"rate": 0.02},
}


def apply_discount(tier, subtotal):
    rate = CAMPAIGNS[tier]["rate"]
    return subtotal * (1 - rate)
