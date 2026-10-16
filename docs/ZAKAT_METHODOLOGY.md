# Salah — Zakat: verified methodology, sources and limitations

Phase 11 research register. **Every rule the calculator applies is recorded here with the source that
supports it.** Nothing in the implementation is asserted without an entry below.

Access date for every source listed: **2026-10-16**. Sources were retrieved and read on that date; prices,
figures and live values quoted from them are as of that date and are **not** reproduced in the app.

> This document is a record of *what sources say*, not a fatwa. Salah is not religiously certified by any
> scholar or institution, and no such claim is made anywhere in the app. Where sources disagree, the
> disagreement is reproduced rather than resolved — see §7.

---

## 1. Payment to the research phase

Implementation was gated on this document. The topics the brief requires were each researched and have an
entry below, and **three material disagreements were found** rather than a single "agreed" answer:

| Brief topic | Where answered | Verdict |
|---|---|---|
| Gold- and silver-based nisab thresholds | §3 | **Disagreement — two weight sets in circulation** |
| Actual weights and units | §2, §3 | Established, with arithmetic shown |
| Gram / traditional-unit conversion | §2 | Established |
| When the obligation begins | §5 | Established |
| Role of the lunar year (Hawl) | §5 | Established; exceptions documented |
| Asset categories included | §4 | Established; scope narrowed in §8 |
| Cash, bank balances, savings, foreign currency | §4.1 | Established |
| Gold and silver; personal-use jewellery | §4.2 | **Disagreement — Hanafi vs. majority** |
| Business inventory and trade assets | §4.3 | **Disagreement — market value vs. landed cost** |
| Receivables; doubtful/unrecoverable debts | §4.4 | Established (Hanafi), with school differences noted |
| Which liabilities may be deducted | §4.5 | **Disagreement — Hanafi vs. Shafi'i vs. Maliki** |
| Immediate expenses, instalments, long-term liabilities | §4.5 | Established; long-term excluded |
| The applicable rate and the nisab relationship | §3, §6 | Established |

---

## 2. Units and conversions

### 2.1 The tola

The **tola** is the traditional South Asian unit for precious metals and is the unit the sources below use
for the nisab weights.

| Claim | Value | Source |
|---|---|---|
| 1 tola in grams | 11.6638 g | `gramstotola.com` (retrieved 2026-10-16); `iifl.com` ("1 tola gold means approximately 11.66 grams. Conversion Formula Grams = Tola × 11.6638"); corroborated by the sources in §3, which state the nisab as "7.5 tola" and "87.48 grams" as the same quantity |
| 1 troy ounce in grams | 31.1034768 g | Arithmetic constant; the tola is defined as 3/8 of a troy ounce |

**Arithmetic check (performed by hand for this document, not taken from any source):**

```
1 tola  = 3/8 × 31.1034768 g = 11.6638038 g
7.5 tola  × 11.6638038 g = 87.4785 g   → published as 87.48 g
52.5 tola × 11.6638038 g = 612.3497 g  → published as 612.36 g
```

The 87.48 g figure follows from the tola definition to within 0.002 g. The 612.36 g figure does **not**:
the arithmetic gives 612.3497 g, so the commonly published 612.36 g is 0.01 g higher than a strict
to-la-conversion produces. Salah therefore carries the published figures as named constants
(`ZakatNisabWeight.INTERNATIONAL.goldGrams = 87.48`, `silverGrams = 612.36`) rather than recomputing them from the tola, so
that its threshold is the one its sources state and not a value this project derived.

**Which nisab applies depends on the unit the user selected, and this is deliberate.** When the unit is
`GRAM`, the published constants above are the threshold: 87.48 g of gold or 612.36 g of silver. When the unit
is `TOLA` or `VORI`, the threshold is the **traditional figure the sources state** — exactly **7.5 tola of
gold** and **52.5 tola of silver** — multiplied by the configured `gramsPerUnit` (11.6638). The reason is the
gap in the arithmetic above: 7.5 × 11.6638 = **87.4785 g**, which is 0.0015 g *below* the published 87.48 g
constant, so a user who enters the traditional nisab in tola would be told they are below the threshold they
actually hold. Comparing the converted weight against the published gram constant would make the traditional
nisab unreachable in its own unit. The traditional-unit branch therefore uses the traditional threshold, and
the two branches agree to within a fifth of a percent, which is the size of the discrepancy in the sources
themselves. The `BANGLADESH` weights (85 g / 595 g) are published only in grams, so they stay as configured
whichever unit is selected.

### 2.2 Units the calculator offers

| Unit | Grams | Notes |
|---|---|---|
| Gram | 1 | The unit every source states the nisab in |
| Tola | 11.6638 | Traditional in Bangladesh and across South Asia; the unit local gold markets quote in |
| Vori | 11.6638 | The Bengali name for the same unit — **not** a separate unit. Treated as an alias of tola so a user cannot enter the same quantity in both. |

**Held for verification, and therefore not implemented:** `mithqal` and `dirham`. Two sources state the
nisab as 85 g gold / 595 g silver, which is the classical figure that arises from 20 mithqal and 200 dirham.
This document does **not** assert that derivation, because no retrieved source states the mithqal and dirham
weights it rests on. See §3.2 — the 85 g / 595 g threshold is offered as *a threshold stated by a named
source*, not as a mithqal calculation.

### 2.3 Currency

No exchange rate is fetched or hardcoded. The user enters amounts in one currency and selects it; a second
currency may be added by entering the rate themselves (§6). BDT is offered as the default display currency
because the app's audience is Bangladeshi, per `ZakatCurrency` — it is a default, not an assumption that
every asset is in BDT.

---

## 3. Nisab

### 3.1 The established threshold

Both figures come from the same statement in the sources, and are supported by multiple independent
institutions:

> "The nisab was set by Prophet Muhammad (peace be upon him) at a rate equivalent to **87.48 grams of gold**
> or **612.36 grams of silver**."
> — Islamic Relief Worldwide, *Nisab*, <https://islamic-relief.org/nisab/>

| Source | Organisation | Rule supported |
|---|---|---|
| <https://islamic-relief.org/nisab/> | Islamic Relief Worldwide | 87.48 g gold / 612.36 g silver; "for one full lunar year" |
| <https://www.muslimaid.org/zakat-calculator/> | Muslim Aid (UK) | "The nisab for gold is based on 87.48 grams, while the nisab for silver is 612.36 grams" |
| <https://www.tcf.org.pk/zakat-calculator/> | The Citizens Foundation (Pakistan) | "the equivalent of 87.48 grams (7.5 tola) of gold and 612.36 grams (52.5 tola) of silver" |
| <https://nzf.org.uk/knowledge/zakat-on-gold-silver-jewellery/> | National Zakat Foundation (UK) | Zakat on gold and silver; Hanafi vs. majority on jewellery (§4.2) |

The last of these is the only retrieved source that states the nisab **in tola as well as grams**
("7.5 tola", "52.5 tola"), which is what makes the tola conversion in §2.1 checkable rather than assumed.

### 3.2 Disagreement: 85 g / 595 g

A **second, materially different pair of weights is published by a reputable Bangladeshi organisation**:

> "one possesses more than **85 grams of gold or 595 grams of silver** or cash equivalent to that"
> — Dhaka Ahsania Mission, *Rules of Zakat*, <https://zakatdonation.org.bd/Rules-of-Zakat.php>

The same 85 g / 595 g pair also appears in <https://humanappealusa.org/news/2026/7/what-is-nisab-the-zakat-threshold-explained> (Human Appeal USA, July 2026): "Islamic scholarship sets nisab at the value of either 85 grams of gold or 595 grams of silver."

**This matters and is not cosmetic.** The gold threshold differs by 2.48 g (≈2.9%) and the silver threshold
by 17.36 g (≈2.9%), which moves the point at which zakat becomes due. Against the same metal price, a
person holding 86 g of gold owes zakat under the 85 g threshold and does not under the 87.48 g one.

**What this document does not claim.** It does **not** claim to know why the two pairs differ, and it does
not assert that 85 g / 595 g "is the mithqal calculation". The obvious hypothesis — 20 mithqal and 200
dirham — cannot be stated as fact here, because no retrieved source gives the mithqal and dirham masses that
would produce it. A claim about *why* two scholarly conventions differ, made without a source for the
convention, would be exactly the invented rule the brief forbids.

**What Salah does.** Both pairs are implemented as selectable nisab bases (`ZakatNisabMethod`), each
labelled with the organisation that publishes it, and neither is presented as the agreed one. See §8.2.

### 3.3 Disagreement: which metal's nisab to use

Islamic Relief's own reconsideration of this question, reproduced because it establishes a rule rather than
only recording a preference:

> "Many scholars say that it is better to use the silver nisab since it will increase the amount of charity
> distributed; others say that the gold nisab is closer to the nisab in use at the time of the blessed
> Prophet (peace be upon him). However, **if your assets consist entirely of gold, you must use the gold
> nisab, and similarly, if they consist entirely of silver then the silver nisab must be used.**"
> — Islamic Relief Worldwide, *Nisab* (emphasis added)

The third sentence is a rule, not a preference, so Salah implements it as one: when the only metal held is
gold, the gold nisab is selected and the silver option is not offered; when only silver is held, the
reverse (§6). Otherwise the choice is the user's and the screen explains both positions.

---

## 4. Asset categories and how each is valued

### 4.1 Cash, bank balances, savings, foreign currency

| Rule | Source |
|---|---|
| "Zakat should be paid at 2.5% on all cash balance and bank balances in your savings, current or FD accounts." | Dhaka Ahsania Mission, *Zakat Calculation*, <https://zakatdonation.org.bd/Calculation.php> |
| Cash and bank balances are zakatable wealth held for a lunar year above nisab | Islamic Relief, *Nisab* (nisab and hawl conditions) |

**The Dhaka Ahsania Mission page adds a practical qualification that Salah surfaces rather than hides:**
"Usually it happens that the balance keeps on changing as per personal requirements... the best way is to pay
on remaining amount on the day of calculation." The calculator therefore values cash **as at the date of
calculation** the user supplies, and says so.

**Foreign currency** is zakatable on the same basis. Salah does not fetch a rate: the user enters the amount
and the rate they used, and the breakdown shows both. An unentered rate leaves the row out of the total and
puts the result into the incomplete state (§7.4) rather than being treated as zero.

### 4.2 Gold and silver — with a documented disagreement

**This is the sharpest disagreement in the whole feature, and Salah does not resolve it.**

| School | Ruling on personal-use gold/silver jewellery | Source |
|---|---|---|
| **Hanafi** | **Zakat is due on all gold and silver, including jewellery that is worn.** | NZF UK, <https://nzf.org.uk/knowledge/zakat-on-gold-silver-jewellery/> ("According to the Hanafi school, Zakat is due on this gold and silver"); NZF Canada, <https://www.nzfcanada.com/research/zakat-on-jewelry> ("The official Hanafi opinion is that Zakat is payable on all gold and silver, including jewelry"); Islamic Relief, *Zakat FAQ*, <https://islamic-relief.org/zakat-faq/> ("Any gold or silver you possess is zakatable, including daily jewellery, according to the Hanafi school, because these two metals have intrinsic monetary value") |
| **Majority (Shafi'i, Maliki, Hanbali)** | **Jewellery kept for permissible personal use is exempt.** | NZF UK (same page: "jewellery that is kept for personal use should be exempt from Zakat"); Islamweb fatwa 89106, <https://www.islamweb.net/en/fatwa/89106/zakat-on-gold-jewelry> ("the opinion of the majority of Muslim jurists and that of some Companions is that there is no Zakah on jewellery that is used as an adornment... If it is not worn except on special occasions, then Zakah has to be paid"); IslamQA (Shafi'i), <https://islamqa.org/shafii/shafiifiqh/30030/does-one-pay-zakat-on-gold-jewelry/> |
| **MUIS Fatwa Committee (Singapore)** | "the payment of zakat on gold jewellery is originally not obligatory (wajib) as it is religiously permissible for women to wear gold jewellery" | <https://www.muis.gov.sg/resources/khutbah-and-religious-advice/fatwa/zakat-on-gold-jewellery--english/> (fatwa of 1995, reaffirmed) |

Note that the two NZF positions and the Islamweb position **agree on the existence of the difference**;
they differ on which ruling applies. Islamic Relief's FAQ states the Hanafi ruling as the one it follows.
Since Bangladesh is predominantly Hanafi, the Hanafi ruling is Salah's **default** — but it is a default
selection the user can change, not a claim that the other ruling is wrong (§7).

**Bangladeshi convention on stone weight — recorded, not implemented.** Dhaka Ahsania Mission's calculation
page states: "A Reduction of 2% from the weight of Jewellery can be allowed towards studded stones jewellery
& 25% from the weight of KUNDAN Jewellery." A general "reduce by 2%" rule was **not implemented**, because
the source gives two different percentages for two named jewellery types and Salah cannot know which the
user's jewellery is. Instead the calculator asks for the **net weight of gold or silver**, and its help text
says that some Bangladeshi organisations allow stone weight to be excluded. The user's own net weight is
therefore used, rather than a percentage this project chose on their behalf.

**Silver: which silver.** Dhaka Ahsania Mission applies zakat to silver "in Pure form or Jewellery, Utensils,
Decorative items and all household items including crockery, cutlery made of silver... at 2.5% of the
prevailing market rates". Salah supports one silver weight, with the unit and net weight the user enters.

### 4.3 Business inventory and trade goods — with a documented disagreement

| Basis | Source | Statement |
|---|---|---|
| **Current market value** | SeekersGuidance (Hanafi), Mawlana Ilyas Patel, checked and approved by Shaykh Faraz Rabbani, <https://seekersguidance.org/answers/hanafi-fiqh/how-do-i-calculate-zakat-on-inventory-and-receivables-in-my-business/> | "Generally, when calculating the net worth of trade goods in your possession, you should use their **current market value**." The answer also sets out the three valuation sets (cost/wholesale, current market, retail) and cites Ibn 'Abidin, *Radd al-Muhtar*. |
| **Landed cost price** | Dhaka Ahsania Mission, *Zakat Calculation* | "The stock must be valued at its **Landed Cost Price**." The same page also directs "Dead Stock should be calculated on scrap value or its saleable value. Damaged stock should also be valued at its scrap value." |

**These disagree**, and they choose so for different reasons: market value asks what the stock is worth
today, landed cost asks what was paid for it. Salah implements **both, as an explicit user-selected
valuation basis** (§8.3), with each option labelled by the position it represents. It does not silently pick
one, and it does not average them.

Also supported, from the SeekersGuidance answer cited above: goods **in the user's possession** on the
valuation date are included ("If goods arrive before the Zakat date, they will be included"), and the
calculator's help text states that goods in transit and not yet held are the user's call to make.

### 4.4 Receivables, and doubtful debts

The debtor/creditor answer is a full survey of the four schools with primary references, and it gives Salah
a precisely-sourced rule to implement and a set of differences to document.

> "**Debts That Are Likely to Be Repaid** — This refers to debts owed by someone who acknowledges the debt
> and is willing to repay it... **Hanafi and Hanbali Schools**: Zakat on such debt is obligatory each year
> because the wealth is owned by the creditor. However, the creditor is not required to pay the Zakat until
> they receive the debt."
>
> "**Debts That Are Unlikely to Be Repaid** — These are debts owed by someone who is insolvent, denies the
> debt, or delays repayment... **Hanafi School: There is no Zakat on such a debt because ownership is
> incomplete** since the creditor cannot benefit from it."
> — Shaykh Dr. Muhammad Fayez Awad, *Which Debts Affect Zakat for Both Debtor and Creditor?*, SeekersGuidance, 2024-10-24, <https://seekersguidance.org/answers/hanafi-fiqh/which-debts-affect-zakat-for-both-debtor-and-creditor/>

**Implemented:** two separate receivable fields — **collectible receivables** (included in zakatable
wealth) and **doubtful/unrecoverable receivables** (excluded, and shown in the breakdown as excluded with
the reason). This follows the Hanafi position the source attributes to the school, and it is why the
calculator has two fields rather than one: a single "receivables" box could not express the ruling.

**Documented but not implemented:** the Shafi'i position that zakat on a doubtful debt is paid for all
previous years upon receipt, and the Maliki one-year-on-receipt position (same source). Salah does not
model "zakat owed on receipt" because that requires tracking years of a debt's history, which is a
different feature; §9 records it as unsupported rather than approximated.

### 4.5 Liabilities, and the disagreement about deducting them

This is the second disagreement that changes the answer, and the source survey is unusually clear about it:

> "**First Opinion**: Debt prevents the obligation of Zakat, except in the case of crops and fruits. This is
> the view of the **Hanafis**. [Sarakhsi, *al-Mabsut*; Kasani, *Bada'i' al-Sana'i'*; Ibn 'Abidin, *Radd
> al-Muhtar*]
>
> **Second Opinion**: Debt prevents the obligation of Zakat on **hidden** wealth such as gold, silver, and
> trade goods, but does not prevent it on **visible** wealth like livestock and crops. This is the view of
> the **Malikis**.
>
> **Third Opinion**: Debt does **not** prevent the obligation of Zakat at all. This is the relied-upon
> opinion among the **Shafi'is**."
> — SeekersGuidance, *Which Debts Affect Zakat...* (above)

The same answer gives the reasoning behind the split (Ibn Rushd, *Bidayat al-Mujtahid*): the schools differ
on whether zakat is "a right established in the wealth for the poor" or "an act of worship".

**Implemented — Hanafi (default):** eligible liabilities are deducted from eligible assets before the nisab
comparison, so a debt that consumes the wealth can remove the obligation. **Available — Shafi'i:** debts are
not deducted, so wealth is compared to nisab gross. Both are selectable, and the screen states which is in
force and what it does. The Maliki position is **not** implemented, because it requires the calculator to
classify every asset as hidden or visible, which is a larger change than this phase; §9 records it.

**Which liabilities are deductible — and which are not.** Dhaka Ahsania Mission, *Zakat Calculation*:
"if you have any pending tax payable to the govt, as of the date of Zakat Calculation, then the same may be
deducted... If you have taken any loans from any person or institution, and if you have not already deducted
the same from any of the above sections, then you can deduct your Payables over here." Islamic Relief's FAQ
takes a broader view for living costs: "If a person possesses wealth exceeding the nisab threshold, but has
to pay rent, purchase food, clothing, etc., for themselves and their dependants, then these costs may be
deducted from one's wealth."

Salah implements:

| Liability | Treatment | Basis |
|---|---|---|
| Debts due now, and payments falling due within the coming year | **Deductible** | Dhaka Ahsania Mission ("deduct your Payables"); Hanafi view above |
| Tax payable as at the valuation date | **Deductible** | Dhaka Ahsania Mission, explicitly |
| The outstanding balance of a **long-term** liability (e.g. a mortgage or a multi-year loan) | **Not deducted — shown as excluded** | No retrieved source permits deducting the whole outstanding balance. Islamic Relief's FAQ permits *costs payable*, and the brief's own warning is explicit: "Do not automatically subtract... the entire outstanding balance of every long-term loan." Only the portion due within the year is deductible, and the user enters that portion in the first row. |
| Living expenses for the household (rent, food, clothing) | **Not deducted — shown as excluded, with the disagreement stated** | Islamic Relief's FAQ permits this; no other retrieved source does, and it is the broadest reading in the register. Salah does not deduct it and says so, because deducting it silently would lower every user's zakat on the strength of one source. |

**The distinction the brief asks for is implemented as two separate rows**, so the same liability cannot be
entered twice: the deductible row is the amount due within the year, and the excluded long-term row is
displayed in the breakdown as excluded, with the reason. There is no "total debts" box.

### 4.6 Categories deliberately out of scope

Each of these has a **different rate or a different trigger** from the 2.5% / lunar-year calculation, so
including them in a generic calculator would apply one category's rules to another — the specific error the
brief forbids. All are recorded as unsupported in the screen and in §9.

| Category | Why excluded | Source |
|---|---|---|
| Agricultural produce (ushr) | Rate is 10% (rain-fed) / 5% (irrigated), **and** the lunar-year condition does not apply: the trigger is the harvest | Dhaka Ahsania Mission, *Zakat Calculation*: "The passing of One year does not apply for agricultural produce." Also lists the 10%/5% rates and 1,100 kg minimum |
| Livestock (an'am) | Rate is in animals, not currency: "one animal/bird for every 40 animals owned" | Dhaka Ahsania Mission, *Zakat Calculation* |
| Minerals, oil, rikaz (buried treasure) | Fifth (khums) rather than 2.5%; no retrieved source in this register establishes the conditions in enough detail | Not independently verified in this phase |
| Shares, provident funds, insurance, bonds | Sources acknowledge valuation difficulty: "This can only be estimated as it is difficult to calculate the exact profit or loss between an accounting year" (Dhaka Ahsania Mission, on partnership capital). Marketable shares have a market value; unquoted ones do not | Not independently verified in this phase |
| Real estate held for resale as a business | Dhaka Ahsania Mission: "Zakat is payable on the Market Value" if held to sell — but this is inventory of the business, and a separate "property held for resale" category was not verified in enough detail to implement safely | Dhaka Ahsania Mission, *Zakat Calculation* |
| Personal residence and rental property buildings | Dhaka Ahsania Mission: "Zakat is not payable on personal residential House even if you have more than one and meant for residential purpose only. Also Zakat is not applicable on Property given on rent... However Zakat is payable on the rental income." Rental income is ordinary cash and can be entered in the cash rows | Dhaka Ahsania Mission, *Zakat Calculation* |

---

## 5. Hawl — the lunar year

| Rule | Statement | Source |
|---|---|---|
| The year | Nisab is "the minimum amount of wealth a Muslim must own **for one full lunar year**" | Islamic Relief, *Nisab* |
| When the year begins | "The zakat year begins on the date on which you were first in possessions of wealth above the nisab. This will be your seed date" | Islamic Relief, *Nisab* |
| Wealth dipping below nisab mid-year | "As long as you are in possession of wealth above the nisab threshold on your seed date, then zakat will be due, even if your wealth dipped below the nisab for some or most of the year." | Islamic Relief, *Nisab* |
| The year's length | "one complete lunar year" / "approximately 354 days" | Islamic Relief, *Nisab*; <https://zakatcalculatorz.com/zakat-calculator> (secondary, cited only for the 354-day figure and **not** used as a calculation constant) |
| What resets the seed date | "The only situation in which your seed date will change is if you were to become totally bankrupt and lose all your assets and belongings." | Islamic Relief, *Nisab* |
| Uncertainty | "If you are not sure of your seed date, then estimate it to the best of your ability." | Islamic Relief, *Nisab* |

**What Salah implements.** The user may enter the date their wealth first reached nisab. The calculator then
computes an **estimated** next anniversary and displays it labelled as an estimate, stating that the lunar
year is about 354 days, that the Hijri year drifts about 11 days earlier each Gregorian year, and that the
user must verify the date themselves. It **never** says a payment is due.

**What Salah does not implement, and why.** It does not convert a Hijri date to a Gregorian one, because
that requires a Hijri calendar with a documented month-length convention, and the app's existing Hijri
support (`CalendarFormatting`, `HijriAdjustment`) is a *display* adjustment for the calendar screen, not a
basis for a religious obligation. It does not model "zakat owed on receipt" for doubtful debts (§4.4). And
it does not state that a payment is due, because — as the brief requires — it has not collected the personal
circumstances that would determine that.

---

## 6. The rate, the comparison, and the calculation flow

| Rule | Statement | Source |
|---|---|---|
| Rate | 2.5% | Dhaka Ahsania Mission, *Rules of Zakat* ("Zakat is to be given at the rate of 2.5% (2.5 taka per hundred)"); SeekersGuidance, *Zakat on inventory and receivables* ("2.5% of surplus wealth held for one complete lunar year above the nisab" — Joe Bradford, *How to Calculate Your Zakat*) |
| Comparison | Net zakatable wealth is compared to the nisab of the **selected** metal | Islamic Relief, *Nisab* |

**Flow implemented** (`ZakatCalculator`), each step a separate, reported value:

1. **Eligible assets by category** — cash, foreign-currency cash (at the user's rate), gold (weight × price),
   silver (weight × price), business inventory (at the selected valuation basis), collectible receivables.
2. **Total eligible assets.**
3. **Eligible deductible liabilities** — the Hanafi row, if the selected school deducts debts.
4. **Net zakatable wealth** = 2 − 3, floored at zero.
5. **The nisab threshold** = the selected metal's weight × the price the user entered for that metal, or the
   nisab the user entered directly if they prefer to state it as an amount.
6. **Whether the threshold is met** — net wealth ≥ threshold.
7. **The zakat due** = 2.5% of net zakatable wealth, and only when the threshold is met.

Step 5 is the reason the metal price matters at all: the nisab is a *weight*, and comparing a weight to a
currency balance requires a price. The price is therefore an input, never a constant — see §7.3.

---

## 7. Transparency rules the implementation must obey

### 7.1 No invented rules

Every rule above has an entry naming a source. Where no source was found, the category is out of scope (§4.6)
or the calculation is marked unsupported (§9). No rule was inferred from another category's rules.

### 7.2 Disagreement is surfaced, not averaged

Three disagreements change the result materially and are **user-selectable**, each labelled with the
position it represents: the nisab weight set (§3.2), the jewellery ruling (§4.2), the inventory valuation
basis (§4.3), and the debt treatment (§4.5). Wherever one is in force the screen names it. No option is
described as the agreed one, and no option is described as invalid.

### 7.3 No price is fabricated, and no stale price is called live

Every price the calculator uses is a **price field the user can see and edit**. A published reference rate
(§8.5) may fill one of those fields when the user has not entered their own, and every price is printed in
the breakdown beside the threshold it produced. Salah does not invent a rate, has no fallback constant, and
never marks a rate live: the screen states plainly that the nisab in currency depends on the price in force
and that the app does not verify it.

The reference rates come from records an administrator has already fetched, reviewed and **published**; the
app reads only those, checks their publication status itself, and contacts no market-price provider directly.
Where the available metadata cannot establish that a price was live — a provider's own update timestamp, or
the moment a record was fetched — the label says what the timestamp actually is rather than presenting it as a
verified market-effective time. When no usable published rate exists, manual entry is the supported
mechanism, and it is never disabled.

### 7.4 Incomplete inputs are shown as incomplete

A category whose required value is missing (a gold weight with no price, a foreign-currency amount with no
rate) is **excluded from the total and reported as incomplete**, and the result is labelled as an estimate
on incomplete inputs. Missing input is never silently treated as zero, and the total is never presented as
if complete.

### 7.5 Calculated versus estimated

The result is labelled **calculated** when every category with data has the values it needs and the
threshold is met; **estimate** when any row is incomplete or when a user-supplied price or rate is doing the
work; and **nothing is due** when the threshold is not met. The three are distinct statuses with their own
words, and each is visually distinct: a completed calculation that produces an amount, an estimate waiting
for a value, and a completed calculation whose answer is that no payment is due under the rules in force are
three different statements, and the screen does not let two of them share a word.

---

## 8. What Salah implements, and why

### 8.1 School of jurisprudence

**Hanafi is the default**, because Bangladesh is predominantly Hanafi and because the two Hanafi sources in
this register (SeekersGuidance's Hanafi answers and the Dhaka Ahsania Mission) are the most specific to the
audience. The Shafi'i alternative is offered for the two rules where it changes the answer — debts (§4.5)
and jewellery (§4.2) — and no attempt is made to implement a full madhhab system, which would require
verifying far more rules than this phase could.

### 8.2 Nisab

| Method | Gold | Silver | Labelled as |
|---|---|---|---|
| `INTERNATIONAL` (default) | 87.48 g | 612.36 g | "87.48 g gold / 612.36 g silver — the weights published by Islamic Relief Worldwide, Muslim Aid, NZF and The Citizens Foundation" |
| `BANGLADESH` | 85 g | 595 g | "85 g gold / 595 g silver — the weights published by Dhaka Ahsania Mission" |

The weights above are the thresholds **when the weight unit is `GRAM`**. When the selected unit is `TOLA` or
`VORI`, the `INTERNATIONAL` threshold is the traditional figure the sources state — exactly **7.5 tola of
gold** or **52.5 tola of silver** — multiplied by the configured `gramsPerUnit` of 11.6638, giving 87.4785 g
and 612.3495 g. The reason is given in §2.1: the strict tola conversion of the published gram constant falls
just short of it, so a user entering the traditional nisab in its own unit would otherwise be told they hold
less than the nisab. The `BANGLADESH` weights are published only in grams and are used unchanged for every
unit.

Metal choice: gold-based or silver-based, with the "all of one metal ⇒ that metal's nisab" rule from §3.3
enforced rather than merely advised. The rule applies only when the assets are **entirely** one metal: cash, a
balance in another currency, stock, a receivable, or a row still waiting for a value all mean the wealth is not
one metal alone, and the user's own choice then stands. A liability is never counted as such an asset — a debt
is what the total is reduced by, not a holding.

### 8.3 Inventory valuation

`MARKET_VALUE` (default, Hanafi — SeekersGuidance) and `LANDED_COST` (Dhaka Ahsania Mission). Both offered;
neither defaulted silently.

### 8.4 Persistence and privacy

**Nothing is saved. There is no repository, no DataStore key, no file.**

Every figure a user enters lives in the calculator's view model, is recalculated on each keystroke, and is gone
when the screen closes. The brief permits this explicitly — §12 asks for the behaviour to be explained rather
than for persistence — and it is the strongest privacy posture available: data that is never written cannot leak,
cannot be backed up, cannot be recovered from a discarded device, and cannot be subpoenaed from a synchronised
account.

The consequences, stated rather than hidden:

* **The values do not survive the process.** Backgrounding Salah long enough for Android to kill the process
  returns the user to an empty form. This is the real cost of the choice, and it is the reason the screen has a
  confirmed "clear everything" — retyping a dozen figures is a genuine loss, so the action that discards them is
  deliberate.
* **Nothing is logged.** No amount, holding or debt is written to log output anywhere in this feature. The only
  values that reach a log at all are the ones in failing test assertions.
* **No user data over the network, no analytics, no account.** The `ZakatCalculator` is a pure function of its
  inputs and cannot reach a network even in principle, because it has no collaborator of any kind — a source
  check in `ZakatNetworkIsolationTest` asserts that. The screen's one remote read is of *published reference
  prices* (§8.5) and sends none of the user's fields; it has no parameter through which a weight, a balance, a
  debt or a result could be passed. Nothing is uploaded, and there is no telemetry or account.
* **The engine is offline by construction**, so the calculation works with no connection once the screen is
  open, and a failed price read leaves the calculator exactly as usable as it was.

If persistence is added later it must be local-only (the existing DataStore), must disclose what is stored, and
must not be the vehicle for any synchronisation of financial data.

### 8.5 Published reference prices (Android)

Salah can fill the gold and silver price fields from **published reference-price records**, so a user who does
not know today's rate does not have to look one up. What was implemented, and what it deliberately is not:

**Where the prices come from.** Supabase is the source. The app performs one read-only query against the
published-price table (`metal_prices`) with the app's existing **publishable (anon) key** — never a
`service_role` or secret key, which would bypass the row-level security the design depends on. The records are
fetched from the upstream provider, validated, reviewed and published by an **administrator workflow that is
outside this app**; the app never calls that provider, and never writes a price.

**Which record is used.** For each metal the app selects a record that is published (**`is_active`**, and
`verification_status = verified` — both re-checked client-side so a misconfigured database cannot serve an
unreviewed row), in the default purity **K22**, and in **BDT**. Exactly one such record is used. If the response
contains two usable records for the same metal, purity and currency, the app reports a conflict and uses
neither: choosing between two approvals would be inventing a policy. A row that exists but is inactive, pending
or rejected is never presented as a current approved rate, and a price is never inferred from the newest
timestamp.

**The unit and the precision.** A reference price is **BDT per gram** as an exact decimal. The JSON number's
own text is read into a `BigDecimal` — never through `Double` or `Float` — and a missing, malformed, zero or
negative price is rejected rather than turned into zero. There is no hardcoded fallback rate anywhere in the
feature.

**How it reaches the calculator.** The retrieval lives behind its own data source, repository and one use case,
and the calculator is untouched by it: it still receives a price through the same `ZakatInputs` it always did,
and stays a pure function (`ZakatNetworkIsolationTest` asserts it mentions no networking type). The read happens
once when the screen is created and then only when the user asks to refresh; nothing fetches on a keystroke or a
recomposition.

**Editing, and never overwriting an edit.** A user's own price is a local, in-memory session override. The app
remembers only *which* price fields the user typed into — nothing about the value is written anywhere — and a
published rate is offered only into a field the user has left alone. A refresh never replaces a typed price, and
the screen offers to restore the published rate explicitly. A published rate never changes `nisabWeight`,
`nisabMetal` or any other methodology choice.

**When there is no usable price.** The screen says so, keeps manual entry working, never fills a field with
zero, and leaves the calculator's own incomplete/estimate behaviour exactly as §7.4 describes. One metal's
failure never stops the other metal's rate being used. Nothing crashes and nothing is blocked.

**Labels.** The source name and any timestamps are shown as what they are: a reviewed/effective date only when
the record carries `effective_at`; `provider_last_update` is labelled as the provider's own update and
`fetched_at`/`updated_at` as when the record was retrieved or last changed. None of them is presented as proof
that a market price was live at that moment, and the upstream provider is not described as an official BAJUS
API or a guaranteed live rate. A published reference rate is a market convention, not the resale value of any
particular item — the screen and the resource strings say so.

**A note on this phase.** The Android side of this integration is implemented and unit-tested against synthetic
fixtures. Whether a *live* published rate can actually be retrieved depends on the backend: the `metal_prices`
table, the published records, and a read policy that permits this app's publishable key must exist in the
deployment. If they do not, the app reports "no published reference price" (or the specific failure) and manual
entry is used — which is the behaviour the tests pin. No end-to-end live retrieval is claimed here.

## 9. Limitations and unsupported cases

Stated here, and stated in the app's own limitations section, because a calculator that hides these is
asserting more than its sources support.

1. **Not religiously certified.** No scholar or institution has reviewed, approved or certified this
   calculator. The sources above are cited as the basis of the rules, not as approval of the software.
2. **Only Hanafi and the two listed Shafi'i divergences are implemented.** Maliki and Hanbali treatments are
   recorded but not selectable.
3. **The Maliki hidden/visible-wealth distinction is not implemented** (§4.5).
4. **"Zakat owed on receipt" for doubtful debts is not implemented** (§4.4). Salah excludes doubtful
   receivables entirely, following Hanafi, and says so. Under the Shafi'i and Maliki positions some zakat
   may be owed on receipt, which Salah does not calculate.
5. **Agricultural produce, livestock, minerals/rikaz, shares, provident funds and insurance are not
   supported** (§4.6), because their rates or triggers differ from this calculator's.
6. **Real estate held for resale is not supported** (§4.6).
7. **No Hijri-to-Gregorian conversion for the hawl date.** The anniversary is an arithmetic estimate from
   the seed date; the user must verify it (§5).
8. **The calculator cannot determine anyone's obligation.** It compares wealth to a threshold and reports the
   arithmetic. Whether zakat is due depends on facts it does not collect (ownership, the completion of the
   hawl, debts, dependants) and on the school the user follows.
9. **A price Salah offers is a reference, not a valuation.** Salah reads *published* gold and silver reference
   rates (§8.5) and may fill an untouched price field with one, but it does not verify any rate, does not own
   the intake process, and does not calculate what a particular item would fetch. Foreign-currency rates are
   still entirely the user's (§4.1), and every price is editable. Where the deployment publishes no usable
   record, manual entry is used.
10. **The 2% / 25% jewellery stone-weight reductions are not implemented** (§4.2).
11. **No zakat on gold or silver held as a business is separated from personal holdings.** Both are one
    weight field with one price, which is correct for the Hanafi calculation but cannot express a user who
    follows the majority ruling for their personal jewellery and the Hanafi one for trade stock. The
    jewellery selector applies to the whole gold and silver weight.

---

## 10. Independent verification of the implementation

The rules above were established from sources; the arithmetic was then checked **by hand, independently of
the code**, and the hand-computed values are the expected values in the tests. The working is reproduced
here so a reader can check it without running anything.

**Case A — above the nisab, gold only.** Gold 100 g at BDT 12,000/g = **1,200,000 BDT**. Nisab, gold-based,
international weights: 87.48 g × 12,000 = **1,049,760 BDT**. Net wealth 1,200,000 ≥ 1,049,760, so zakat is
due. Zakat = 1,200,000 × 0.025 = **30,000 BDT**.

**Case B — below the nisab.** Cash 500,000 BDT, gold 10 g at 12,000 = 120,000 BDT. Total 620,000 BDT. Nisab
gold-based 1,049,760 BDT. 620,000 < 1,049,760 ⇒ **no zakat**.

**Case C — exactly at the nisab.** Gold 87.48 g at 12,000 = 1,049,760 BDT = the threshold. The brief's
"wealth meeting the selected nisab" case: the comparison is `>=`, and Islamic Relief states the obligation
attaches to wealth "above the nisab" while the classical formulation is "reaching" it — Salah uses `>=` and
the test pins that boundary explicitly with its assumption stated.

**Case D — silver nisab, which is the lower threshold.** Cash 200,000 BDT, silver price 150 BDT/g. Silver
nisab 612.36 g × 150 = 91,854 BDT. 200,000 ≥ 91,854 ⇒ zakat due = 5,000 BDT. With the gold nisab at
12,000 BDT/g (1,049,760 BDT) the same person would owe **nothing** — which is the disagreement in §3.3
expressed as a number, and is the reason the choice is the user's.

**Case E — a deductible liability.** Cash 500,000, gold 60 g at 12,000 = 720,000. Assets 1,220,000. A
liability due within the year of 400,000. Hanafi: net 820,000; nisab 1,049,760 ⇒ **no zakat**. Shafi'i:
gross 1,220,000 ≥ 1,049,760 ⇒ zakat = (1,220,000 × 0.025) = **30,500 BDT**. The same inputs, two selectable
answers, both sourced in §4.5.

**Case F — a liability larger than the assets.** Cash 100,000; liability due within the year 300,000. Net =
−200,000, floored to **0**; no zakat. The floor is asserted, because a negative net wealth must not produce
a negative zakat.

**Case G — unit conversion.** 5 tola of gold = 5 × 11.6638 = **58.319 g**. At 12,000 BDT/g = **699,828
BDT**. Checked by hand, and separately by the arithmetic in §2.1.

**Case H — rounding.** Net wealth 1,234,567.89 BDT. Zakat = 30,864.19725 BDT. The rounding policy is
half-up to 2 decimal places at the **final step only**, giving **30,864.20 BDT**. The test asserts both the
half-up behaviour on a trailing 5 and that no intermediate value was rounded — the same case computed with
intermediate rounding to 2 dp would give 30,864.19 and is asserted *not* to be the answer.

**Case I — incomplete inputs.** Gold 20 g entered with no gold price. Gold is excluded from the total and the
result is an estimate, not a calculation; the amount is not treated as 1,200,000, 120,000 or zero.

**Case J — the two nisab weight sets on the same wealth.** Gold 86 g at 12,000 = 1,032,000 BDT. International
gold nisab 1,049,760 ⇒ **no zakat**. Bangladesh gold nisab 85 g × 12,000 = 1,020,000 ⇒ **zakat due,
25,800 BDT**. This is §3.2 with a number attached: 86 g of gold is the boundary case the two weight sets
disagree about.

**What this verification does *not* establish.** Passing these cases shows that the code computes what §6
says it should. It does **not** show that the rules are correct, and it is not a scholarly review. The brief
requires that distinction to be stated, and it is.

---

## 11. Source register

Every URL below was retrieved and read on **2026-10-16** for this phase. No source, quotation or attribution
in this document was produced from memory.

| # | Title | Organisation / author | URL | Rules it supports |
|---|---|---|---|---|
| S1 | *Nisab* | Islamic Relief Worldwide | <https://islamic-relief.org/nisab/> | 87.48 g gold / 612.36 g silver; one full lunar year; seed date; dipping below nisab; bankruptcy resets the year; gold-vs-silver choice and the all-one-metal rule; living costs deductible (recorded, not implemented) |
| S2 | *Zakat FAQ* | Islamic Relief Worldwide | <https://islamic-relief.org/zakat-faq/> | Hanafi ruling that gold and silver including daily jewellery are zakatable |
| S3 | *Zakat Calculator* | Muslim Aid (UK) | <https://www.muslimaid.org/zakat-calculator/> | Corroborates 87.48 g / 612.36 g |
| S4 | *Zakat Calculator* | The Citizens Foundation (Pakistan) | <https://www.tcf.org.pk/zakat-calculator/> | Corroborates 87.48 g (7.5 tola) / 612.36 g (52.5 tola) — the tola link used in §2.1 |
| S5 | *Zakat on Gold and Silver* | National Zakat Foundation (UK) | <https://nzf.org.uk/knowledge/zakat-on-gold-silver-jewellery/> | Hanafi vs. majority on personal-use jewellery |
| S6 | *Zakat on Jewelry* | NZF Canada | <https://www.nzfcanada.com/research/zakat-on-jewelry> | Hanafi: zakat on all gold and silver including jewellery |
| S7 | *Zakat on gold jewelry* (fatwa 89106) | Islamweb | <https://www.islamweb.net/en/fatwa/89106/zakat-on-gold-jewelry> | Majority: no zakat on jewellery used as adornment; zakat if worn only occasionally |
| S8 | *Does One pay Zakat on Gold Jewelry?* | IslamQA (Shafi'i) | <https://islamqa.org/shafii/shafiifiqh/30030/does-one-pay-zakat-on-gold-jewelry/> | Shafi'i position on jewellery |
| S9 | *Zakat on gold jewellery* (fatwa) | Majlis Ugama Islam Singapura (MUIS) Fatwa Committee | <https://www.muis.gov.sg/resources/khutbah-and-religious-advice/fatwa/zakat-on-gold-jewellery--english/> | Institutional confirmation of the majority position |
| S10 | *Which Debts Affect Zakat for Both Debtor and Creditor?* (Shaykh Dr. Muhammad Fayez Awad) | SeekersGuidance | <https://seekersguidance.org/answers/hanafi-fiqh/which-debts-affect-zakat-for-both-debtor-and-creditor/> | Four-school debt survey with primary references; Hanafi debtor rule; collectible vs. doubtful receivables; Shafi'i and Maliki positions; Ibn Rushd on why they differ |
| S11 | *How Do I Calculate Zakat on Inventory and Receivables in My Business?* (Mawlana Ilyas Patel, approved by Shaykh Faraz Rabbani) | SeekersGuidance | <https://seekersguidance.org/answers/hanafi-fiqh/how-do-i-calculate-zakat-on-inventory-and-receivables-in-my-business/> | Inventory at current market value (Hanafi, citing Ibn 'Abidin); the three valuation sets; unpaid shipped orders not zakatable; goods held before the date included |
| S12 | *Zakat Calculation* | Dhaka Ahsania Mission Zakat Fund (Bangladesh) | <https://zakatdonation.org.bd/Calculation.php> | 2.5%; market value at the date of valuation; the 2%/25% stone reductions; silver items; cash and bank balances; loans given as cash in hand; payables and tax deductible; inventory at **landed cost**; dead/damaged stock at scrap; agricultural produce 10%/5% and **no hawl**; livestock 1 per 40; no zakat on personal residence; zakat on rental income |
| S13 | *Rules of Zakat* | Dhaka Ahsania Mission Zakat Fund (Bangladesh) | <https://zakatdonation.org.bd/Rules-of-Zakat.php> | 2.5% rate; **85 g gold / 595 g silver**; assets listed as zakatable for a Bangladeshi audience |
| S14 | *What Is Nisab?* | Human Appeal USA | <https://humanappealusa.org/news/2026/7/what-is-nisab-the-zakat-threshold-explained> | Independent corroboration of the 85 g / 595 g pair |
| S15 | *How to Calculate Your Zakat* | Joe Bradford | <https://joebradford.substack.com/p/how-to-calculate-your-zakat> | 2.5% rate on surplus wealth held one lunar year above nisab |
| S16 | *1 tola in grams* | gramstotola.com | <https://gramstotola.com/tola-to-grams/> | 1 tola = 11.6638 g |
| S17 | *How many grams is 1 tola of gold* | IIFL | <https://www.iifl.com/blogs/gold-loan/how-many-grams-is-1-tola-of-gold> | Corroborates 1 tola ≈ 11.66 g and the 11.6638 conversion factor |

### Sources considered and **not** used as authority

| Source | Why not |
|---|---|
| <https://zakatcalculatorz.com/zakat-calculator>, <https://zakatcalculatorz.com/nisab-calculator> | Commercial calculators with no named methodology. Cited in §5 only for the "about 354 days" figure, which S1 already supports qualitatively; **no calculation constant comes from them** |
| <https://www.halalwallet.us/…> business-asset guides | Assert rules (e.g. an "AMJA approach" to payables) without reproducing the evidence; the same rules are available in S10–S12 with citations, so these were not relied on |
| <https://www.amuslimcf.org/zakat-nisab-2026-current-thresholds-and-how-to-calculate/>, <https://humanconcern.org/zakat-calculator/>, <https://obhizatrik.org/zakat-calculator/> | Undated or unsourced assertion pages, including a live-currency nisab figure with no stated methodology |
| <https://zakatfinance.com/…> | Paywalled "evidence-first" guides whose evidence is not visible without a purchase |
| Region-18/19 sources returned by the search for "tola" (e.g. TOLA New York, individual namesakes) | Irrelevant results; recorded so the search is reproducible |
| Forum threads (<https://www.muftisays.com/…>) and social media posts | Excluded by the source-quality rule in the brief, even where they agreed with S5–S7 |

### Known gaps in the register

* No source was found that states the **mithqal** and **dirham** masses, so §3.2 does not explain the 85 g /
  595 g pair's derivation. The pair is implemented as a *published threshold*, not as a derived one.
* No **ZATCA (Saudi)** or **AMJA** primary document was read; Saudi ZATCA's *Summary Fiqh of Zakat
  Estimation* was located at <https://zatca.gov.sa/en/MediaCenter/Publications/Documents/Summary%20Fiqh%20of%20Zakat%20Estimation%20Book-En.pdf>
  but was not retrieved in this phase, so nothing in the implementation rests on it. Retrieving it is listed
  in §9 as an open item for a future phase.
* The **Maliki** and **Hanbali** positions are recorded from S10 (a secondary survey that does cite primary
  works) but were not verified against those primary works directly.

---

## 12. Related documents

* `docs/TASBIH_COUNTER.md` — Phase 10, and the pattern this feature follows for local storage, localisation
  and testing.
* `docs/ARCHITECTURE.md` §4 — the layering this feature's engine obeys: pure domain logic, no Android, no
  formatting inside a composable.
* `docs/LOCALIZATION.md` — the resource architecture and numerals rule the Zakat screen follows.
