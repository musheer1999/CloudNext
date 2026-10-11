# SHOP-42 — Loyalty members get an extra 5% off

Demo ticket for the sample repo https://github.com/musheer1999/failureforge-sample
(Gerrit change "Members get an extra 5% off (SHOP-42)" on review.gerrithub.io).
Paste the text below into the portal's ticket box when running FailureForge on that change.

---

**Summary:** Loyalty members get an extra 5% off on top of any coupon.

**Description:** To reward loyalty, members should pay 5% less than non-members. The member
bonus is added to the coupon discount, if any.

**Acceptance criteria:**
1. A member with a 10% coupon gets 15% off in total.
2. A member without a coupon gets 5% off.
3. Non-members are not affected.
4. The final price is never negative: the total discount can never be more than 100%.
5. Coupon discounts outside 0–100% are still rejected.

---

**Planted bug (for us, not for the AI):** the change adds the 5% but does not cap the total
at 100%, so a member with a coupon of 96% or more gets a negative price
(e.g. ₹1 item, 96% coupon, member → −1 paise). The existing tests (10%, 0%) don't catch it.
Expected FailureForge result: a counterexample against acceptance criterion 4, shrunk to about
discount = 96, member = true, smallest price that goes negative = 100 paise.
