package com.datalens.model;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
class ModelOutputValidatorTest {
    final jakarta.validation.ValidatorFactory factory=Validation.buildDefaultValidatorFactory();
    final ModelOutputValidator validator=new ModelOutputValidator(factory.getValidator());
    @AfterEach void close(){factory.close();}
    @Test void acceptsKnownReviewedMetric(){assertThat(validator.parse(ModelFixtures.valid(),ModelFixtures.publication()).plan().metricId()).isEqualTo("sales");}
    @Test void deniesSensitiveAssets(){assertThatThrownBy(()->validator.parse(ModelFixtures.valid(),ModelFixtures.publication("SENSITIVE",1))).hasMessageContaining("校验");}
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","```json\n{}\n```","{\"intent\":{}}"})
    void rejectsMalformedShape(String text){assertThatThrownBy(()->validator.parse(text,ModelFixtures.publication())).hasMessageContaining("校验");}
    @Test void rejectsDuplicateProperties(){reject(ModelFixtures.valid().replace("\"rowLimit\":1000","\"rowLimit\":1000,\"rowLimit\":1000"));}
    @Test void rejectsUnknownProperties(){reject(ModelFixtures.valid().replace("\"rowLimit\":1000","\"rowLimit\":1000,\"sql\":\"DROP TABLE x\""));}
    @Test void rejectsTrailingJson(){reject(ModelFixtures.valid()+" {}");}
    @Test void rejectsHallucinatedMetric(){reject(ModelFixtures.valid().replace("sales","invented"));}
    @Test void rejectsHallucinatedChartAlias(){reject(ModelFixtures.valid().replace("\"measures\":[\"sales\"]","\"measures\":[\"invented\"]"));}
    @Test void rejectsCoercedNumbers(){reject(ModelFixtures.valid().replace("\"rowLimit\":1000","\"rowLimit\":\"1000\""));}
    @Test void rejectsNullPrimitive(){reject(ModelFixtures.valid().replace("\"needsClarification\":false","\"needsClarification\":null"));}
    @Test void rejectsUnknownChartType(){reject(ModelFixtures.valid().replace("\"TABLE\"","\"HTML\""));}
    @Test void rejectsUnsupportedPlanRewrite(){reject(ModelFixtures.valid().replace("\"rowLimit\":1000","\"rowLimit\":500"));}
    @Test void rejectsMissingBoolean(){reject(ModelFixtures.valid().replace("\"needsClarification\":false,",""));}
    @Test void rejectsOversizedText(){reject(" ".repeat(64001));}
    @Test void acceptsExplicitClarification(){String text=ModelFixtures.valid().replace("\"needsClarification\":false","\"needsClarification\":true").replace("\"clarification\":\"\"","\"clarification\":\"Which metric?\"").replace("\"metricId\":\"sales\"","\"metricId\":\"\"").replace("\"measures\":[\"sales\"]","\"measures\":[]");assertThat(validator.parse(text,ModelFixtures.publication()).intent().needsClarification()).isTrue();}
    void reject(String text){assertThatThrownBy(()->validator.parse(text,ModelFixtures.publication())).hasMessageContaining("校验");}
}
